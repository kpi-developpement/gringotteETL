package com.kyntus.gringotts_sync.service;

import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AutoPilot : rend l'app autonome.
 *
 * Chaque tick (60s par défaut) :
 *  1. Watchdog : répare les threads morts.
 *  2. Healer : toujours actif (redémarré s'il est tombé).
 *  3. Mois courant : terminé -> re-scan complet toutes les X minutes (nouveaux EPS + changements d'état).
 *  4. Mois passés (fenêtre glissante) : périodes non terminées reprises ; périodes terminées vérifiées
 *     toutes les X heures (total Bytel vs EPS distincts en base). Trou détecté -> re-scan.
 *  5. EPS fantômes ("{}") remis dans la file du Healer toutes les X heures.
 */
@Slf4j
@Service
public class AutoPilotService {

    private static final String KEY_ENABLED = "ap_enabled";
    private static final String KEY_PHANTOM = "ap_phantom_retry";

    private final SyncOrchestrator orchestrator;
    private final SyncStateRepository syncStateRepository;

    private final boolean enabledByDefault;
    private final int monthsBack;
    private final long currentRefreshMs;
    private final long verifyMs;
    private final long phantomRetryMs;
    private final String healerMode;
    private final List<String> extraPeriods;

    private volatile String status = "Initialisation";
    private volatile String lastTickAt = "-";
    private volatile List<String> lastGaps = List.of();

    public AutoPilotService(
            SyncOrchestrator orchestrator,
            SyncStateRepository syncStateRepository,
            @Value("${kyntus.autopilot.enabled:true}") boolean enabledByDefault,
            @Value("${kyntus.autopilot.months-back:2}") int monthsBack,
            @Value("${kyntus.autopilot.current-refresh-minutes:60}") long currentRefreshMinutes,
            @Value("${kyntus.autopilot.verify-hours:6}") long verifyHours,
            @Value("${kyntus.autopilot.phantom-retry-hours:6}") long phantomRetryHours,
            @Value("${kyntus.autopilot.healer-mode:RADAR}") String healerMode,
            @Value("${kyntus.autopilot.extra-periods:}") String extraPeriods) {
        this.orchestrator = orchestrator;
        this.syncStateRepository = syncStateRepository;
        this.enabledByDefault = enabledByDefault;
        this.monthsBack = Math.max(0, monthsBack);
        this.currentRefreshMs = currentRefreshMinutes * 60_000L;
        this.verifyMs = verifyHours * 3_600_000L;
        this.phantomRetryMs = phantomRetryHours * 3_600_000L;
        this.healerMode = healerMode;
        this.extraPeriods = Arrays.stream(extraPeriods.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
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
        orchestrator.addAlert("[AUTOPILOT] " + (enabled ? "Activé (prise en main < 1 min)" : "Désactivé"));
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("status", status);
        m.put("last_tick", lastTickAt);
        m.put("managed_periods", managedPeriods());
        m.put("last_gaps", lastGaps);
        m.put("throttle", orchestrator.getThrottleStatus());
        return m;
    }

    // ---------------------------------------------------------------- Boucle

    @Scheduled(initialDelayString = "${kyntus.autopilot.initial-delay-ms:30000}",
               fixedDelayString = "${kyntus.autopilot.tick-ms:60000}")
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

            if (orchestrator.isTimeMachineBusy()) {
                status = "Time Machine en cours (" + orchestrator.getCurrentPeriod() + ")";
                return;
            }

            planAndStart();
        } catch (Exception e) {
            log.error("[AUTOPILOT] Erreur tick", e);
            status = "Erreur: " + e.getMessage();
        }
    }

    private void planAndStart() {
        long now = System.currentTimeMillis();
        String current = periodOf(YearMonth.now());
        List<String> toRun = new ArrayList<>();
        Set<String> reset = new LinkedHashSet<>();
        List<String> gaps = new ArrayList<>();

        for (String p : managedPeriods()) {
            int offset = orchestrator.getSavedOffset(p);
            int total = orchestrator.getSavedTotal(p);
            boolean unfinished = total == 0 || offset < total;

            if (p.equals(current)) {
                if (unfinished) {
                    toRun.add(p);
                    if (offset == 0) saveLong("ap_lastscan_" + p, now);
                } else if (now - getLong("ap_lastscan_" + p) > currentRefreshMs) {
                    toRun.add(p);
                    reset.add(p);
                    saveLong("ap_lastscan_" + p, now);
                }
                continue;
            }

            if (unfinished) {
                toRun.add(p);
                continue;
            }

            if (now - getLong("ap_verify_" + p) < verifyMs) continue;
            saveLong("ap_verify_" + p, now);

            status = "Vérification " + p + "...";
            int remote = orchestrator.probeRemoteTotal(p);
            long local = orchestrator.countLocalDistinct(p);
            if (remote <= 0) continue; // Bytel indispo, on réessaiera au prochain cycle de vérification

            if (local < remote) {
                // Trou identique au dernier re-scan => structurel (EPS déplacés / doublons Bytel). On ne boucle pas.
                boolean sameAsLast = getLong("ap_gap_remote_" + p) == remote && getLong("ap_gap_local_" + p) == local;
                if (sameAsLast) {
                    log.info("[AUTOPILOT] {}: écart {} persistant après re-scan, ignoré.", p, remote - local);
                    continue;
                }
                saveLong("ap_gap_remote_" + p, remote);
                saveLong("ap_gap_local_" + p, local);
                gaps.add(p + " (-" + (remote - local) + ")");
                orchestrator.addAlert("[AUTOPILOT] Trou détecté sur " + p + " : " + local + "/" + remote + " EPS -> re-scan");
                toRun.add(p);
                reset.add(p);
            } else {
                log.info("[AUTOPILOT] {} complet ({} / {}).", p, local, remote);
            }
        }

        if (!gaps.isEmpty()) lastGaps = gaps;

        // Mois courant en premier : c'est le plus "vivant".
        toRun.sort(Comparator.comparing((String p) -> !p.equals(current)).thenComparing(Comparator.reverseOrder()));

        if (toRun.isEmpty()) {
            status = "Tout est à jour. Prochain refresh du mois courant dans "
                    + Math.max(0, (currentRefreshMs - (now - getLong("ap_lastscan_" + current))) / 60_000) + " min";
            return;
        }
        if (orchestrator.startAutoSync(toRun, reset)) {
            status = "Sync lancée: " + String.join(", ", toRun);
        }
    }

    private void maybeRetryPhantoms() {
        long now = System.currentTimeMillis();
        if (now - getLong(KEY_PHANTOM) < phantomRetryMs) return;
        saveLong(KEY_PHANTOM, now);
        int n = orchestrator.retryFailedHeals();
        log.info("[AUTOPILOT] {} EPS fantômes remis au Healer.", n);
    }

    // ---------------------------------------------------------------- Helpers

    List<String> managedPeriods() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        YearMonth now = YearMonth.now();
        for (int i = 0; i <= monthsBack; i++) set.add(periodOf(now.minusMonths(i)));
        set.addAll(extraPeriods);
        return new ArrayList<>(set);
    }

    static String periodOf(YearMonth ym) {
        return String.format("%d_M%02d", ym.getYear(), ym.getMonthValue());
    }

    private long getLong(String key) {
        return syncStateRepository.findById(key)
                .map(SyncState::getStateValueStr)
                .filter(s -> s != null && !s.isEmpty())
                .map(s -> { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; } })
                .orElse(0L);
    }

    private void saveLong(String key, long value) {
        SyncState st = syncStateRepository.findById(key).orElse(new SyncState());
        st.setStateKey(key);
        st.setStateValueStr(Long.toString(value));
        syncStateRepository.save(st);
    }

    private void saveInt(String key, int value) {
        SyncState st = syncStateRepository.findById(key).orElse(new SyncState());
        st.setStateKey(key);
        st.setStateValue(value);
        syncStateRepository.save(st);
    }
}
