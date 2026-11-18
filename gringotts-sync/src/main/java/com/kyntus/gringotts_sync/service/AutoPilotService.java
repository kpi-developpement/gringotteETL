package com.kyntus.gringotts_sync.service;

import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.kyntus.gringotts_sync.service.EventLogService.*;

/**
 * AutoPilot — moteur en 3 phases par période, limité à un PÉRIMÈTRE (par défaut : l'année en cours).
 *
 *  ① COLLECTE      : la Time Machine aspire la période depuis Bytel (chaque page est contrôlée en base).
 *  ② ENRICHISSEMENT: le Healer récupère les détails ; un EPS sans détail est ré-essayé avec espacement
 *                    (30 min → 48 h) puis classé "définitif" : la phase se termine toujours.
 *  ③ VÉRIFICATION  : 1 appel léger à Bytel (total) comparé aux EPS distincts en base + EPS orphelins.
 *                    Trou -> nouvelle collecte complète. Croissance -> reprise en fin de liste seulement.
 *  ✅ COMPLET      : les 3 phases sont vertes.
 *
 * Ensuite la période passe en SURVEILLANCE : mois courant re-collecté toutes les X min, mois précédent
 * toutes les X h, mois clos rarement (pas de re-téléchargement complet en boucle), vérification régulière.
 */
@Slf4j
@Service
public class AutoPilotService {

    private static final String KEY_ENABLED = "ap_enabled";
    private static final String KEY_SCOPE_MODE = "scope_mode";
    private static final String KEY_SCOPE_START = "scope_start";

    public static final String SCOPE_CURRENT_YEAR = "CURRENT_YEAR";
    public static final String SCOPE_LAST_12 = "LAST_12";
    public static final String SCOPE_CUSTOM = "CUSTOM";

    private final SyncOrchestrator orchestrator;
    private final SyncStateRepository syncStateRepository;
    private final InterventionRepository interventionRepository;

    private final boolean enabledByDefault;
    private final String defaultScopeMode;
    private final String defaultScopeStart;
    private final long refreshCurrentMs;
    private final long refreshPreviousMs;
    private final long refreshClosedMs;
    private final long verifyMs;
    private final String healerMode;

    private volatile String status = "Initialisation";
    private volatile String lastTickAt = "-";

    private volatile List<Object[]> aggCache = List.of();
    private volatile long aggCachedAt = 0;

    public AutoPilotService(
            SyncOrchestrator orchestrator,
            SyncStateRepository syncStateRepository,
            InterventionRepository interventionRepository,
            @Value("${kyntus.autopilot.enabled:true}") boolean enabledByDefault,
            @Value("${kyntus.autopilot.scope-mode:CURRENT_YEAR}") String defaultScopeMode,
            @Value("${kyntus.autopilot.scope-start:2026_M01}") String defaultScopeStart,
            @Value("${kyntus.autopilot.refresh-current-minutes:60}") long refreshCurrentMinutes,
            @Value("${kyntus.autopilot.refresh-previous-hours:6}") long refreshPreviousHours,
            @Value("${kyntus.autopilot.refresh-closed-hours:168}") long refreshClosedHours,
            @Value("${kyntus.autopilot.verify-hours:6}") long verifyHours,
            @Value("${kyntus.autopilot.healer-mode:RADAR}") String healerMode) {
        this.orchestrator = orchestrator;
        this.syncStateRepository = syncStateRepository;
        this.interventionRepository = interventionRepository;
        this.enabledByDefault = enabledByDefault;
        this.defaultScopeMode = defaultScopeMode;
        this.defaultScopeStart = defaultScopeStart;
        this.refreshCurrentMs = refreshCurrentMinutes * 60_000L;
        this.refreshPreviousMs = refreshPreviousHours * 3_600_000L;
        this.refreshClosedMs = refreshClosedHours * 3_600_000L;
        this.verifyMs = verifyHours * 3_600_000L;
        this.healerMode = healerMode;
    }

    @PostConstruct
    void init() {
        orchestrator.setPeriodListener(this::onPeriodComplete);
        orchestrator.setScopeStart(periodOf(scopeStart()));
    }

    // ================================================================ Périmètre

    public String scopeMode() {
        String m = getStr(KEY_SCOPE_MODE);
        return m == null || m.isBlank() ? defaultScopeMode : m;
    }

    /** Première période du périmètre. */
    public YearMonth scopeStart() {
        YearMonth now = YearMonth.now();
        return switch (scopeMode()) {
            case SCOPE_LAST_12 -> now.minusMonths(11);
            case SCOPE_CUSTOM -> parsePeriod(Optional.ofNullable(getStr(KEY_SCOPE_START)).orElse(defaultScopeStart))
                    .filter(ym -> !ym.isAfter(now)).orElse(YearMonth.of(now.getYear(), 1));
            // Année en cours ; en janvier on garde décembre pour finir le mois écoulé.
            default -> now.getMonthValue() == 1 ? now.minusMonths(1) : YearMonth.of(now.getYear(), 1);
        };
    }

    public String scopeLabel() {
        return switch (scopeMode()) {
            case SCOPE_LAST_12 -> "12 derniers mois";
            case SCOPE_CUSTOM -> "Depuis " + periodOf(scopeStart());
            default -> "Année en cours";
        };
    }

    public void setScope(String mode, String start) {
        String m = mode == null ? SCOPE_CURRENT_YEAR : mode.toUpperCase();
        if (!List.of(SCOPE_CURRENT_YEAR, SCOPE_LAST_12, SCOPE_CUSTOM).contains(m)) {
            throw new IllegalArgumentException("Mode de périmètre inconnu : " + mode);
        }
        if (SCOPE_CUSTOM.equals(m)) {
            YearMonth ym = parsePeriod(start).orElseThrow(() -> new IllegalArgumentException("Période invalide : " + start));
            if (ym.isAfter(YearMonth.now())) throw new IllegalArgumentException("Période dans le futur : " + start);
            saveStr(KEY_SCOPE_START, periodOf(ym));
        }
        saveStr(KEY_SCOPE_MODE, m);
        String sp = periodOf(scopeStart());
        orchestrator.setScopeStart(sp);
        aggCachedAt = 0;
        orchestrator.event(AUTOPILOT, INFO, null, "Périmètre changé : " + scopeLabel() + " (" + sp + " → " + periodOf(YearMonth.now())
                + ", " + scopePeriods().size() + " périodes). Les données hors périmètre sont conservées mais plus rafraîchies.");
    }

    /** Périodes du périmètre, de la plus récente à la plus ancienne. */
    public List<String> scopePeriods() {
        List<String> list = new ArrayList<>();
        YearMonth start = scopeStart();
        for (YearMonth ym = YearMonth.now(); !ym.isBefore(start); ym = ym.minusMonths(1)) list.add(periodOf(ym));
        return list;
    }

    // ================================================================ API

    public boolean isEnabled() {
        return syncStateRepository.findById(KEY_ENABLED)
                .map(SyncState::getStateValue)
                .map(v -> v != null && v == 1)
                .orElse(enabledByDefault);
    }

    public void setEnabled(boolean enabled) {
        saveInt(KEY_ENABLED, enabled ? 1 : 0);
        status = enabled ? "Activé" : "Désactivé";
        orchestrator.event(AUTOPILOT, enabled ? SUCCESS : WARN, null,
                enabled ? "AutoPilot activé (prise en main < 30 s)" : "AutoPilot désactivé");
    }

    public Map<String, Object> describe() {
        List<Map<String, Object>> rows = periodsOverview().stream().filter(r -> Boolean.TRUE.equals(r.get("in_scope"))).toList();
        long complete = rows.stream().filter(r -> List.of("COMPLETE", "EMPTY").contains(r.get("overall"))).count();
        Map<String, Long> phaseCounts = new LinkedHashMap<>();
        phaseCounts.put("collect", rows.stream().filter(r -> !List.of("DONE", "EMPTY").contains(phase(r, "collect"))).count());
        phaseCounts.put("enrich", rows.stream().filter(r -> List.of("DONE", "EMPTY").contains(phase(r, "collect")) && !"DONE".equals(phase(r, "enrich"))).count());
        phaseCounts.put("verify", rows.stream().filter(r -> "DONE".equals(phase(r, "enrich")) && !"DONE".equals(phase(r, "verify"))).count());
        phaseCounts.put("complete", complete);

        long now = System.currentTimeMillis();
        String current = periodOf(YearMonth.now());
        long lastCur = getLong("ap_lastscan_" + current);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("status", status);
        m.put("last_tick", lastTickAt);
        m.put("scope_mode", scopeMode());
        m.put("scope_label", scopeLabel());
        m.put("scope_start", periodOf(scopeStart()));
        m.put("scope_periods", scopePeriods());
        m.put("periods_total", rows.size());
        m.put("periods_complete", complete);
        m.put("completeness", rows.isEmpty() ? 0 : Math.round(complete * 100.0 / rows.size()));
        m.put("phase_counts", phaseCounts);
        m.put("next_current_refresh_min", lastCur == 0 ? 0 : Math.max(0, (refreshCurrentMs - (now - lastCur)) / 60_000));
        m.put("refresh", Map.of(
                "current_min", refreshCurrentMs / 60_000,
                "previous_h", refreshPreviousMs / 3_600_000,
                "closed_h", refreshClosedMs / 3_600_000,
                "verify_h", verifyMs / 3_600_000));
        m.put("throttle", orchestrator.getThrottleStatus());
        m.put("bytel_paused", orchestrator.isBytelPaused());
        // Compat ancienne UI
        m.put("pass", 0);
        m.put("pass_done", complete);
        m.put("pass_total", rows.size());
        m.put("managed_periods", scopePeriods());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String phase(Map<String, Object> row, String key) {
        Object o = row.get(key);
        return o instanceof Map<?, ?> m ? String.valueOf(((Map<String, Object>) m).get("status")) : "";
    }

    private volatile List<Map<String, Object>> overviewCache = null;
    private volatile long overviewAt = 0;

    /** Tableau des phases par période (cache 3 s : lu toutes les 2 s par l'UI). */
    public List<Map<String, Object>> periodsOverview() {
        long t = System.currentTimeMillis();
        if (overviewCache != null && t - overviewAt < 3_000 && aggCachedAt != 0) return overviewCache;
        List<Map<String, Object>> rows = computeOverview();
        overviewCache = rows;
        overviewAt = t;
        return rows;
    }

    private List<Map<String, Object>> computeOverview() {
        long now = System.currentTimeMillis();
        int max = orchestrator.getHealerMaxAttempts();
        String active = orchestrator.getCurrentPeriod();
        boolean tmRunning = orchestrator.isRunning();
        boolean healing = orchestrator.isHealing();
        List<String> queued = orchestrator.getQueuedPeriods();
        String current = periodOf(YearMonth.now());

        Map<String, Object[]> agg = new HashMap<>();
        for (Object[] row : aggregates(max)) if (row[0] != null) agg.put((String) row[0], row);

        List<String> scope = scopePeriods();
        LinkedHashSet<String> periods = new LinkedHashSet<>(scope);
        agg.keySet().stream().filter(p -> parsePeriod(p).isPresent()).forEach(periods::add);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : periods) {
            boolean inScope = scope.contains(p);
            Object[] a = agg.get(p);
            long rowsCount = a == null ? 0 : n(a[1]);
            long distinct = a == null ? 0 : n(a[2]);
            long fresh = a == null ? 0 : n(a[3]);
            long retry = a == null ? 0 : n(a[4]);
            long definitive = a == null ? 0 : n(a[5]);
            int offset = orchestrator.getSavedOffset(p);
            int total = orchestrator.getSavedTotal(p);
            long lastScan = getLong("ap_lastscan_" + p);
            boolean recollect = getInt("ap_recollect_" + p) == 1;
            Map<String, Object> last = parseResult(getStr("ap_result_" + p));
            boolean empty = last != null && Boolean.TRUE.equals(last.get("empty"));
            String[] vres = splitVres(getStr("ap_vres_" + p));
            long vAt = vres == null ? 0 : Long.parseLong(vres[4]);

            // ① Collecte
            String cStatus;
            if (tmRunning && p.equals(active)) cStatus = "ACTIVE";
            else if (queued.contains(p)) cStatus = "QUEUED";
            else if (lastScan == 0) cStatus = offset > 0 ? "PARTIAL" : "TODO";
            else if (recollect) cStatus = "GAP";
            else if (total > 0 && offset > 0 && offset < total) cStatus = "PARTIAL";
            else if (empty) cStatus = "EMPTY";
            else cStatus = "DONE";
            long interval = refreshInterval(p, current);
            Map<String, Object> collect = new LinkedHashMap<>();
            collect.put("status", cStatus);
            collect.put("offset", offset);
            collect.put("remote_total", total);
            collect.put("progress", total > 0 ? Math.min(100, Math.round(offset * 100.0 / total)) : (lastScan > 0 ? 100 : 0));
            collect.put("scans", getInt("ap_scans_" + p));
            collect.put("last_scan", fmt(lastScan));
            collect.put("next_refresh_min", !inScope || lastScan == 0 || interval <= 0 ? null : Math.max(0, (interval - (now - lastScan)) / 60_000));

            // ② Enrichissement
            boolean collected = lastScan > 0;
            String eStatus;
            if (!collected && rowsCount == 0) eStatus = "WAIT";
            else if (fresh == 0 && retry == 0) eStatus = "DONE";
            else if (fresh == 0) eStatus = "RETRY";
            else eStatus = healing && inScope ? "ACTIVE" : "TODO";
            Map<String, Object> enrich = new LinkedHashMap<>();
            enrich.put("status", eStatus);
            enrich.put("fresh", fresh);
            enrich.put("retry", retry);
            enrich.put("definitive", definitive);
            enrich.put("with_detail", Math.max(0, rowsCount - fresh - retry - definitive));
            enrich.put("pct", rowsCount == 0 ? 0 : Math.round((rowsCount - fresh - retry) * 100.0 / rowsCount));

            // ③ Vérification
            String vStatus;
            if (!List.of("DONE", "EMPTY").contains(cStatus)) vStatus = "WAIT";
            else if (vres == null || vAt < lastScan) vStatus = "TODO";
            else vStatus = switch (vres[0]) {
                case "OK" -> "DONE";
                case "GROWTH", "UNREACHABLE" -> "TODO";
                default -> vres[0];
            };
            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("status", vStatus);
            verify.put("at", fmt(vAt));
            verify.put("remote", vres == null ? null : Long.parseLong(vres[1]));
            verify.put("local", vres == null ? null : Long.parseLong(vres[2]));
            verify.put("orphans", vres == null ? null : Long.parseLong(vres[3]));

            String overall;
            if (!inScope) overall = "OUT_OF_SCOPE";
            else if ("EMPTY".equals(cStatus)) overall = "EMPTY";
            else if ("DONE".equals(cStatus) && "DONE".equals(eStatus) && "DONE".equals(vStatus)) overall = "COMPLETE";
            else if ("GAP_PERSISTENT".equals(vStatus)) overall = "ANOMALY";
            else overall = "IN_PROGRESS";

            long gap = vres == null ? 0 : Math.max(0, Long.parseLong(vres[1]) - Long.parseLong(vres[2]));

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("period", p);
            r.put("in_scope", inScope);
            r.put("overall", overall);
            r.put("collect", collect);
            r.put("enrich", enrich);
            r.put("verify", verify);
            // champs "à plat" (matrice / anciennes vues)
            r.put("state", "ACTIVE".equals(cStatus) ? "ACTIVE" : "QUEUED".equals(cStatus) ? "QUEUED"
                    : List.of("COMPLETE", "EMPTY").contains(overall) ? "DONE" : "PENDING");
            r.put("local_rows", rowsCount);
            r.put("local_distinct", distinct);
            r.put("missing_details", fresh);
            r.put("phantoms", retry + definitive);
            r.put("remote_total", total);
            r.put("gap", gap);
            r.put("gap_source", vres == null ? "unknown" : "live");
            r.put("offset", offset);
            r.put("progress", collect.get("progress"));
            r.put("scans", collect.get("scans"));
            r.put("last_scan", collect.get("last_scan"));
            r.put("last_result", last);
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("period")).reversed());
        return rows;
    }

    /** Vérification manuelle (bouton UI). */
    public Map<String, Object> verifyNow(String period) {
        verify(period, System.currentTimeMillis());
        aggCachedAt = 0;
        return periodsOverview().stream().filter(r -> period.equals(r.get("period"))).findFirst().orElse(Map.of());
    }

    /** Re-collecte complète demandée à la main : prise en compte dès que possible. */
    public void requestRecollect(String period) {
        saveInt("ap_recollect_" + period, 1);
        orchestrator.event(AUTOPILOT, INFO, period, "Nouvelle collecte complète de " + period + " demandée.");
        if (orchestrator.isRunning()) orchestrator.injectPriority(period, true);
    }

    // ================================================================ Boucle

    @Scheduled(initialDelayString = "${kyntus.autopilot.initial-delay-ms:30000}",
               fixedDelayString = "${kyntus.autopilot.tick-ms:30000}")
    public synchronized void tick() {
        lastTickAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        try {
            if (!isEnabled()) {
                status = "Désactivé";
                return;
            }

            orchestrator.setScopeStart(periodOf(scopeStart()));
            orchestrator.watchdog();
            if (!orchestrator.isHealerBusy()) orchestrator.startHealer(healerMode);

            long now = System.currentTimeMillis();
            String current = periodOf(YearMonth.now());

            if (orchestrator.isTimeMachineBusy()) {
                if (getLong("ap_lastscan_" + current) > 0 && now - getLong("ap_lastscan_" + current) > refreshCurrentMs) {
                    orchestrator.injectPriority(current, false);
                }
                status = "Collecte " + orchestrator.getCurrentPeriod() + " (" + orchestrator.getQueuedPeriods().size() + " en file)";
                return;
            }

            // ---- ① Collectes dues
            Map<String, String> reasons = new LinkedHashMap<>();
            Set<String> reset = new LinkedHashSet<>();
            for (String p : scopePeriods()) {
                long last = getLong("ap_lastscan_" + p);
                int offset = orchestrator.getSavedOffset(p), total = orchestrator.getSavedTotal(p);
                boolean recollect = getInt("ap_recollect_" + p) == 1;
                long interval = refreshInterval(p, current);
                String reason = null;
                if (last == 0) reason = offset > 0 ? "reprise" : "première collecte";
                else if (recollect) reason = "trou détecté";
                else if (total > 0 && offset > 0 && offset < total) reason = "reprise / nouveaux EPS";
                else if (interval > 0 && now - last > interval) reason = "rafraîchissement";
                if (reason == null) continue;
                reasons.put(p, reason);
                if (recollect || (last > 0 && total > 0 && offset >= total)) reset.add(p);
            }
            if (!reasons.isEmpty()) {
                List<String> order = new ArrayList<>(reasons.keySet());
                List<String> prio = List.of("première collecte", "trou détecté", "reprise", "reprise / nouveaux EPS", "rafraîchissement");
                order.sort(Comparator.comparing((String p) -> !p.equals(current))
                        .thenComparing(p -> prio.indexOf(reasons.get(p)))
                        .thenComparing(Comparator.reverseOrder()));
                if (orchestrator.startAutoSync(order, reset)) {
                    StringBuilder sb = new StringBuilder("Collecte lancée : ");
                    order.forEach(p -> sb.append(p).append(" (").append(reasons.get(p)).append("), "));
                    orchestrator.event(AUTOPILOT, INFO, null, sb.substring(0, sb.length() - 2));
                    status = "Collecte de " + order.size() + " période(s)";
                }
                return;
            }

            // ---- ③ Vérifications dues (max 3 par tick, 1 appel Bytel chacune)
            int verified = 0;
            for (String p : scopePeriods()) {
                if (verified >= 3) break;
                long last = getLong("ap_lastscan_" + p);
                if (last == 0) continue;
                long vAt = getLong("ap_verify_" + p);
                if (vAt >= last && now - vAt < verifyMs) continue;
                verify(p, now);
                verified++;
            }
            aggCachedAt = 0;

            long nextMin = Math.max(0, (refreshCurrentMs - (now - getLong("ap_lastscan_" + current))) / 60_000);
            status = verified > 0 ? verified + " période(s) vérifiée(s)" : "Surveillance — prochain refresh de " + current + " dans " + nextMin + " min";
        } catch (Exception e) {
            log.error("[AUTOPILOT] Erreur tick", e);
            status = "Erreur: " + e.getMessage();
        }
    }

    /** ③ Vérification d'une période : total Bytel vs EPS distincts en base + orphelins. */
    private void verify(String p, long now) {
        int oldTotal = orchestrator.getSavedTotal(p);
        int offset = orchestrator.getSavedOffset(p);
        long lastScan = getLong("ap_lastscan_" + p);
        status = "Vérification " + p + "…";

        int remote = orchestrator.probeRemoteTotal(p);
        if (remote < 0) {
            saveStr("ap_vres_" + p, "UNREACHABLE;0;0;-1;" + now);
            saveLong("ap_verify_" + p, now - verifyMs + 15 * 60_000L); // ré-essai dans 15 min
            return;
        }
        long local = orchestrator.countLocalDistinct(p);
        long scanStart = getLong("ap_scanstart_" + p);
        long orphans = scanStart > 0 && lastScan >= scanStart ? orchestrator.countOrphans(p, scanStart) : -1;

        String st;
        if (remote > local) {
            long gap = remote - local;
            String[] prev = splitVres(getStr("ap_vres_" + p));
            if (oldTotal > 0 && remote > oldTotal && offset >= oldTotal) {
                st = "GROWTH";
                orchestrator.event(AUTOPILOT, INFO, p, p + " : Bytel a " + (remote - oldTotal)
                        + " nouveaux EPS → reprise de la collecte en fin de liste uniquement.");
            } else if (prev != null && List.of("GAP", "GAP_PERSISTENT").contains(prev[0])
                    && Long.parseLong(prev[1]) == remote && Long.parseLong(prev[2]) == local
                    && lastScan > Long.parseLong(prev[4])) {
                st = "GAP_PERSISTENT";
                orchestrator.event(AUTOPILOT, WARN, p, p + " : écart de " + gap + " EPS persistant après une collecte complète "
                        + "(base " + local + " / Bytel " + remote + "). Probablement des doublons ou EPS déplacés côté Bytel — pas de nouvelle collecte auto.");
            } else {
                st = "GAP";
                saveInt("ap_recollect_" + p, 1);
                orchestrator.event(AUTOPILOT, WARN, p, p + " : " + gap + " EPS manquants (base " + local + " / Bytel " + remote
                        + ") → nouvelle collecte complète programmée.");
            }
        } else {
            st = "OK";
            orchestrator.event(AUTOPILOT, SUCCESS, p, p + " vérifiée ✓ base " + local + " / Bytel " + remote + " EPS"
                    + (orphans > 0 ? " · " + orphans + " EPS en base que Bytel ne renvoie plus (déplacés/supprimés)" : ""));
        }
        saveStr("ap_vres_" + p, st + ";" + remote + ";" + local + ";" + orphans + ";" + now);
        saveLong("ap_verify_" + p, now);
    }

    private void onPeriodComplete(SyncOrchestrator.PeriodResult r) {
        long now = System.currentTimeMillis();
        saveLong("ap_lastscan_" + r.period(), now);
        saveInt("ap_scans_" + r.period(), getInt("ap_scans_" + r.period()) + 1);
        saveInt("ap_recollect_" + r.period(), 0);
        saveStr("ap_result_" + r.period(), r.inserted() + ";" + r.updated() + ";" + r.localDistinct() + ";"
                + r.remoteTotal() + ";" + r.durationMs() + ";" + (r.empty() ? 1 : 0) + ";" + r.changed() + ";" + r.orphans());
        aggCachedAt = 0;
    }

    // ================================================================ Helpers

    private long refreshInterval(String p, String current) {
        if (p.equals(current)) return refreshCurrentMs;
        if (p.equals(periodOf(YearMonth.now().minusMonths(1)))) return refreshPreviousMs;
        return refreshClosedMs;
    }

    private List<Object[]> aggregates(int max) {
        if (System.currentTimeMillis() - aggCachedAt > 30_000) {
            aggCache = interventionRepository.periodAggregates(max);
            aggCachedAt = System.currentTimeMillis();
        }
        return aggCache;
    }

    private static long n(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    static String periodOf(YearMonth ym) {
        return String.format("%d_M%02d", ym.getYear(), ym.getMonthValue());
    }

    static Optional<YearMonth> parsePeriod(String p) {
        try {
            String[] parts = p.split("_M");
            return Optional.of(YearMonth.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String[] splitVres(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] a = s.split(";");
        return a.length >= 5 ? a : null;
    }

    private static Map<String, Object> parseResult(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] a = s.split(";");
        if (a.length < 6) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inserted", Integer.parseInt(a[0]));
        m.put("updated", Integer.parseInt(a[1]));
        m.put("local", Long.parseLong(a[2]));
        m.put("remote", Integer.parseInt(a[3]));
        m.put("duration", SyncOrchestrator.formatDuration(Long.parseLong(a[4])));
        m.put("empty", "1".equals(a[5]));
        m.put("changed", a.length > 6 ? Integer.parseInt(a[6]) : null);
        m.put("orphans", a.length > 7 ? Long.parseLong(a[7]) : null);
        return m;
    }

    private static String fmt(long epochMs) {
        if (epochMs <= 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
    }

    private int getInt(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).filter(Objects::nonNull).orElse(0);
    }

    private String getStr(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValueStr).orElse(null);
    }

    private long getLong(String key) {
        String s = getStr(key);
        if (s == null || s.isEmpty()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private void saveLong(String key, long value) { saveStr(key, Long.toString(value)); }

    private void saveStr(String key, String value) {
        SyncState st = syncStateRepository.findById(key).orElse(new SyncState());
        st.setStateKey(key);
        st.setStateValueStr(value);
        syncStateRepository.save(st);
    }

    private void saveInt(String key, int value) {
        SyncState st = syncStateRepository.findById(key).orElse(new SyncState());
        st.setStateKey(key);
        st.setStateValue(value);
        syncStateRepository.save(st);
    }
}
