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
 * AutoPilot : boucle infinie sur TOUTES les périodes.
 *
 * Passe #1 : toutes les périodes (de start-period jusqu'au mois courant), mois courant d'abord puis du plus
 * récent au plus ancien. Quand la passe est finie -> courte pause -> passe #2, #3... sans jamais s'arrêter.
 * Chaque période est donc re-scannée à chaque passe (nouveaux EPS, changements d'état, trous comblés).
 *
 * En plus, pendant une passe, le mois courant est ré-inséré en tête de file toutes les X minutes.
 * Watchdog + Healer toujours actif + EPS fantômes remis en file régulièrement.
 */
@Slf4j
@Service
public class AutoPilotService {

    private static final String KEY_ENABLED = "ap_enabled";
    private static final String KEY_PHANTOM = "ap_phantom_retry";
    private static final String KEY_PASS = "ap_pass";
    private static final String KEY_PASS_STARTED = "ap_pass_started";

    private final SyncOrchestrator orchestrator;
    private final SyncStateRepository syncStateRepository;
    private final InterventionRepository interventionRepository;

    private final boolean enabledByDefault;
    private final YearMonth startPeriod;
    private final long currentRefreshMs;
    private final long passGapMs;
    private final long phantomRetryMs;
    private final String healerMode;
    private final List<String> extraPeriods;

    private volatile String status = "Initialisation";
    private volatile String lastTickAt = "-";

    private volatile List<Object[]> aggCache = List.of();
    private volatile long aggCachedAt = 0;

    public AutoPilotService(
            SyncOrchestrator orchestrator,
            SyncStateRepository syncStateRepository,
            InterventionRepository interventionRepository,
            @Value("${kyntus.autopilot.enabled:true}") boolean enabledByDefault,
            @Value("${kyntus.autopilot.start-period:2024_M01}") String startPeriod,
            @Value("${kyntus.autopilot.current-refresh-minutes:60}") long currentRefreshMinutes,
            @Value("${kyntus.autopilot.pass-gap-minutes:2}") long passGapMinutes,
            @Value("${kyntus.autopilot.phantom-retry-hours:6}") long phantomRetryHours,
            @Value("${kyntus.autopilot.healer-mode:RADAR}") String healerMode,
            @Value("${kyntus.autopilot.extra-periods:}") String extraPeriods) {
        this.orchestrator = orchestrator;
        this.syncStateRepository = syncStateRepository;
        this.interventionRepository = interventionRepository;
        this.enabledByDefault = enabledByDefault;
        this.startPeriod = parsePeriod(startPeriod).orElse(YearMonth.of(2024, 1));
        this.currentRefreshMs = currentRefreshMinutes * 60_000L;
        this.passGapMs = passGapMinutes * 60_000L;
        this.phantomRetryMs = phantomRetryHours * 3_600_000L;
        this.healerMode = healerMode;
        this.extraPeriods = Arrays.stream(extraPeriods.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @PostConstruct
    void init() {
        orchestrator.setPeriodListener(this::onPeriodComplete);
    }

    // ---------------------------------------------------------------- API

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
                enabled ? "AutoPilot activé (prise en main < 1 min)" : "AutoPilot désactivé");
    }

    public Map<String, Object> describe() {
        int pass = currentPass();
        List<String> all = managedPeriods();
        long done = all.stream().filter(p -> getInt("ap_pass_done_" + p) >= pass).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("status", status);
        m.put("last_tick", lastTickAt);
        m.put("pass", pass);
        m.put("pass_started", fmt(getLong(KEY_PASS_STARTED)));
        m.put("pass_done", done);
        m.put("pass_total", all.size());
        m.put("managed_periods", all);
        m.put("throttle", orchestrator.getThrottleStatus());
        m.put("bytel_paused", orchestrator.isBytelPaused());
        return m;
    }

    /** Tableau par période : base locale, Bytel, healer, passes. */
    public List<Map<String, Object>> periodsOverview() {
        int pass = currentPass();
        String active = orchestrator.getCurrentPeriod();
        boolean tmRunning = orchestrator.isRunning();
        List<String> queued = orchestrator.getQueuedPeriods();

        Map<String, Object[]> agg = new HashMap<>();
        for (Object[] row : aggregates()) {
            if (row[0] != null) agg.put((String) row[0], row);
        }

        LinkedHashSet<String> periods = new LinkedHashSet<>(managedPeriods());
        agg.keySet().stream().filter(p -> parsePeriod(p).isPresent()).forEach(periods::add);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : periods) {
            Object[] a = agg.get(p);
            int offset = orchestrator.getSavedOffset(p);
            int total = orchestrator.getSavedTotal(p);
            long distinct = a == null ? 0 : ((Number) a[2]).longValue();

            String state;
            if (tmRunning && p.equals(active)) state = "ACTIVE";
            else if (queued.contains(p)) state = "QUEUED";
            else if (getInt("ap_pass_done_" + p) >= pass) state = "DONE";
            else state = "PENDING";

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("period", p);
            r.put("state", state);
            r.put("local_rows", a == null ? 0 : ((Number) a[1]).longValue());
            r.put("local_distinct", distinct);
            r.put("missing_details", a == null ? 0 : ((Number) a[3]).longValue());
            r.put("phantoms", a == null ? 0 : ((Number) a[4]).longValue());
            r.put("remote_total", total);
            // Le "manque" n'a de sens qu'après un scan complet : sinon on reprend le constat du dernier scan.
            Map<String, Object> last = parseResult(getStr("ap_result_" + p));
            boolean scannedFully = total > 0 && offset >= total && !"ACTIVE".equals(state);
            long gap;
            String gapSource;
            if (scannedFully) { gap = Math.max(0, total - distinct); gapSource = "live"; }
            else if (last != null && !Boolean.TRUE.equals(last.get("empty"))) {
                gap = Math.max(0, ((Number) last.get("remote")).longValue() - ((Number) last.get("local")).longValue());
                gapSource = "last_scan";
            } else { gap = 0; gapSource = "unknown"; }
            r.put("gap", gap);
            r.put("gap_source", gapSource);
            r.put("offset", offset);
            r.put("progress", total > 0 ? Math.min(100, Math.round(offset * 100.0 / total)) : 0);
            r.put("scans", getInt("ap_scans_" + p));
            r.put("pass_done", getInt("ap_pass_done_" + p));
            r.put("last_scan", fmt(getLong("ap_lastscan_" + p)));
            r.put("last_result", last);
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("period")).reversed());
        return rows;
    }

    // ---------------------------------------------------------------- Boucle

    @Scheduled(initialDelayString = "${kyntus.autopilot.initial-delay-ms:30000}",
               fixedDelayString = "${kyntus.autopilot.tick-ms:30000}")
    public synchronized void tick() {
        lastTickAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        try {
            if (!isEnabled()) {
                status = "Désactivé";
                return;
            }

            orchestrator.watchdog();

            if (!orchestrator.isHealerBusy()) {
                orchestrator.startHealer(healerMode);
            }

            maybeRetryPhantoms();

            long now = System.currentTimeMillis();
            String current = periodOf(YearMonth.now());
            int pass = currentPass();

            if (orchestrator.isTimeMachineBusy()) {
                if (now - getLong("ap_lastscan_" + current) > currentRefreshMs) {
                    orchestrator.injectPriority(current);
                }
                status = "Passe #" + pass + " — scan " + orchestrator.getCurrentPeriod()
                        + " (" + orchestrator.getQueuedPeriods().size() + " en file)";
                return;
            }

            List<String> all = managedPeriods();
            List<String> pending = new ArrayList<>(all.stream().filter(p -> getInt("ap_pass_done_" + p) < pass).toList());

            if (pending.isEmpty()) {
                long ended = getLong("ap_pass_ended_" + pass);
                if (ended == 0) {
                    ended = now;
                    saveLong("ap_pass_ended_" + pass, now);
                    long started = getLong(KEY_PASS_STARTED);
                    orchestrator.event(AUTOPILOT, SUCCESS, null, "Passe #" + pass + " terminée : " + all.size()
                            + " périodes scannées" + (started > 0 ? " en " + SyncOrchestrator.formatDuration(now - started) : "") + ".");
                }
                if (now - ended < passGapMs) {
                    status = "Passe #" + pass + " terminée — passe #" + (pass + 1) + " dans " + ((passGapMs - (now - ended)) / 1000) + "s";
                    return;
                }
                saveInt(KEY_PASS, pass + 1);
                saveLong(KEY_PASS_STARTED, now);
                pending = new ArrayList<>(all);
                orchestrator.event(AUTOPILOT, INFO, null, "Démarrage de la passe #" + (pass + 1) + " sur " + all.size() + " périodes.");
            } else if (getLong(KEY_PASS_STARTED) == 0) {
                saveLong(KEY_PASS_STARTED, now);
                orchestrator.event(AUTOPILOT, INFO, null, "Démarrage de la passe #" + pass + " sur " + all.size() + " périodes.");
            }

            // Mois courant d'abord, puis du plus récent au plus ancien.
            pending.sort(Comparator.comparing((String p) -> !p.equals(current)).thenComparing(Comparator.reverseOrder()));

            Set<String> reset = new LinkedHashSet<>();
            for (String p : pending) {
                int off = orchestrator.getSavedOffset(p), tot = orchestrator.getSavedTotal(p);
                if (tot > 0 && off >= tot) reset.add(p); // déjà complète -> on re-scanne depuis 0
            }

            if (orchestrator.startAutoSync(pending, reset)) {
                status = "Passe #" + currentPass() + " — " + pending.size() + " périodes en file";
            }
        } catch (Exception e) {
            log.error("[AUTOPILOT] Erreur tick", e);
            status = "Erreur: " + e.getMessage();
        }
    }

    private void onPeriodComplete(SyncOrchestrator.PeriodResult r) {
        long now = System.currentTimeMillis();
        saveInt("ap_pass_done_" + r.period(), currentPass());
        saveInt("ap_scans_" + r.period(), getInt("ap_scans_" + r.period()) + 1);
        saveLong("ap_lastscan_" + r.period(), now);
        saveStr("ap_result_" + r.period(), r.inserted() + ";" + r.updated() + ";" + r.localDistinct() + ";"
                + r.remoteTotal() + ";" + r.durationMs() + ";" + (r.empty() ? 1 : 0));
        aggCachedAt = 0; // force refresh du tableau
    }

    private void maybeRetryPhantoms() {
        long now = System.currentTimeMillis();
        if (now - getLong(KEY_PHANTOM) < phantomRetryMs) return;
        saveLong(KEY_PHANTOM, now);
        orchestrator.retryFailedHeals();
    }

    // ---------------------------------------------------------------- Helpers

    private List<Object[]> aggregates() {
        if (System.currentTimeMillis() - aggCachedAt > 30_000) {
            aggCache = interventionRepository.periodAggregates();
            aggCachedAt = System.currentTimeMillis();
        }
        return aggCache;
    }

    int currentPass() {
        int p = getInt(KEY_PASS);
        return p <= 0 ? 1 : p;
    }

    List<String> managedPeriods() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        YearMonth now = YearMonth.now();
        for (YearMonth ym = now; !ym.isBefore(startPeriod); ym = ym.minusMonths(1)) set.add(periodOf(ym));
        set.addAll(extraPeriods);
        return new ArrayList<>(set);
    }

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
