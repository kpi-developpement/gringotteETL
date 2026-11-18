package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import com.kyntus.gringotts_sync.integration.BytelThrottle;
import com.kyntus.gringotts_sync.integration.PhpApiClient;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.kyntus.gringotts_sync.service.EventLogService.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrator {

    /** Résultat d'un scan complet d'une période. */
    public record PeriodResult(String period, int inserted, int updated, int remoteTotal,
                               long localDistinct, long durationMs, boolean empty, int changed, long orphans) {}

    private final PhpApiClient phpApiClient;
    private final InterventionRepository interventionRepository;
    private final SyncStateRepository syncStateRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final BytelThrottle throttle;
    private final EventLogService events;
    private final ActivityFeed feed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kyntus.healer.chunk-size:200}")
    private int healerChunkSize;

    @Value("${kyntus.healer.batch-size:10}")
    private int healerBatchSize;

    /** Après N essais sans détail, l'EPS est classé "définitif" et sort de la file. */
    @Value("${kyntus.healer.max-attempts:5}")
    private int healerMaxAttempts;

    /** Périmètre : périodes >= scopeStart (ex "2026_M01"). "" = tout. Poussé par l'AutoPilot. */
    private volatile String scopeStart = "";

    private volatile NamedParameterJdbcTemplate named;

    private volatile boolean isRunning = false;
    private volatile boolean isHealing = false;
    private volatile String healerMode = "TIME_MACHINE";

    private volatile int healTotal = 0;
    private volatile int healCurrent = 0;
    private volatile long syncStartTime = 0;
    private volatile int totalProcessedSinceStart = 0;
    private volatile String currentEta = "En attente...";

    private volatile String timeMachineStatus = "En veille";
    private volatile String healerStatus = "En veille";
    private final List<String> recentAlerts = new CopyOnWriteArrayList<>();

    private volatile long totalHealerProcessed = 0;
    private volatile long totalPeriodProcessed = 0;

    private static final int TIME_MACHINE_BATCH = 250;

    private final ConcurrentLinkedDeque<String> periodQueue = new ConcurrentLinkedDeque<>();
    private volatile String currentPeriod = null;

    // Compteurs de la période en cours
    private volatile int periodInserted = 0;
    private volatile int periodUpdated = 0;
    private volatile int periodChanged = 0;
    private volatile long periodStartedAt = 0;
    private volatile String scanStartMarkedFor = null;

    private volatile Consumer<PeriodResult> periodListener = r -> {};

    private Thread timeMachineThread;
    private Thread healerThread;

    private final ExecutorService healerThreadPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "healer-worker");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------ Métriques Healer
    private final AtomicLong hEnriched = new AtomicLong();
    private final AtomicLong hNotFound = new AtomicLong();
    private final AtomicLong hFailed = new AtomicLong();
    private final AtomicLong hFailedBatches = new AtomicLong();
    private final AtomicLong hRecovered = new AtomicLong();
    private final AtomicLong hDefinitive = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger emptyBatchStreak = new java.util.concurrent.atomic.AtomicInteger();
    private final ConcurrentLinkedDeque<long[]> healWindow = new ConcurrentLinkedDeque<>(); // [ts, count]
    private final List<Map<String, Object>> recentLots = new CopyOnWriteArrayList<>();
    private volatile long lastHealSuccessAt = 0;
    private volatile String lastHealError = null;
    private volatile long healerStartedAt = 0;

    // ------------------------------------------------------------ Getters
    public boolean isRunning() { return isRunning; }
    public boolean isHealing() { return isHealing; }
    public String getHealerMode() { return healerMode; }
    public int getHealTotal() { return healTotal; }
    public int getHealCurrent() { return healCurrent; }
    public String getCurrentEta() { return currentEta; }
    public String getTimeMachineStatus() { return timeMachineStatus; }
    public String getHealerStatus() { return healerStatus; }
    public List<String> getRecentAlerts() { return recentAlerts; }
    public long getTotalHealerProcessed() { return totalHealerProcessed; }
    public long getTotalPeriodProcessed() { return totalPeriodProcessed; }
    public String getCurrentPeriod() { return currentPeriod; }
    public String getThrottleStatus() { return throttle.describe(); }
    public boolean isBytelPaused() { return throttle.isPaused(); }
    public List<String> getQueuedPeriods() { return new ArrayList<>(periodQueue); }
    public int getPeriodInserted() { return periodInserted; }
    public int getPeriodUpdated() { return periodUpdated; }
    public long getPeriodStartedAt() { return periodStartedAt; }
    public int getPeriodChanged() { return periodChanged; }
    public int getHealerMaxAttempts() { return healerMaxAttempts; }
    public String getScopeStart() { return scopeStart; }
    public void setScopeStart(String scope) {
        String v = scope == null ? "" : scope;
        if (!v.equals(this.scopeStart)) { this.scopeStart = v; queueCachedAt = 0; }
    }

    private NamedParameterJdbcTemplate named() {
        if (named == null) named = new NamedParameterJdbcTemplate(jdbcTemplate);
        return named;
    }

    public void setPeriodListener(Consumer<PeriodResult> listener) { this.periodListener = listener; }

    // ------------------------------------------------------------ Journal

    /** Ancienne API : déduit la source / le niveau depuis le message. */
    public void addAlert(String message) {
        String src = SYSTEM;
        if (message.contains("[TIME MACHINE]") || message.contains("[SMART RESCAN]")) src = TM;
        else if (message.contains("[HEALER]")) src = HEALER;
        else if (message.contains("[AUTOPILOT]")) src = AUTOPILOT;
        else if (message.contains("[MAINTENANCE]")) src = MAINTENANCE;

        String lvl = INFO;
        String low = message.toLowerCase();
        if (message.contains("✅") || message.contains("🎉") || low.contains("terminé") || low.contains("succès")) lvl = SUCCESS;
        if (message.contains("⚠️") || low.contains("surcharg") || low.contains("timeout") || low.contains("trou")
                || low.contains("bloqué") || low.contains("pare-feu") || low.contains("watchdog")) lvl = WARN;
        if (low.contains("erreur") || low.contains("critique")) lvl = ERROR;

        String clean = message.replaceAll("^\\s*\\S*\\s*\\[[A-Z ]+\\]\\s*", "").replaceAll("^\\[[A-Z ]+\\]\\s*", "");
        event(src, lvl, null, clean.isBlank() ? message : clean);
    }

    public void event(String source, String level, String period, String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        recentAlerts.add(0, "[" + time + "] [" + source + "] " + message);
        while (recentAlerts.size() > 30) recentAlerts.remove(recentAlerts.size() - 1);
        if (WARN.equals(level) || ERROR.equals(level)) log.warn("[{}] {}", source, message);
        else log.info("[{}] {}", source, message);
        events.log(source, level, period, message);
        feed.log(switch (source) { case TM -> "TM"; case HEALER -> "HEALER"; case BYTEL -> "BYTEL"; case AUTOPILOT -> "AUTO"; default -> "SYS"; },
                switch (level) { case SUCCESS -> "OK"; case WARN -> "WARN"; case ERROR -> "ERROR"; default -> "INFO"; },
                (period != null ? "[" + period + "] " : "") + message);
    }

    // ------------------------------------------------------------ Contrôle / AutoPilot

    /** Répare les flags "zombies" (flag à true mais thread mort). */
    public synchronized boolean watchdog() {
        boolean fixed = false;
        if (isRunning && (timeMachineThread == null || !timeMachineThread.isAlive())) {
            isRunning = false;
            timeMachineStatus = "Récupéré par le watchdog";
            event(SYSTEM, WARN, currentPeriod, "Watchdog : thread Time Machine mort, relance automatique.");
            fixed = true;
        }
        if (isHealing && (healerThread == null || !healerThread.isAlive())) {
            isHealing = false;
            healerStatus = "Récupéré par le watchdog";
            event(SYSTEM, WARN, null, "Watchdog : thread Healer mort, relance automatique.");
            fixed = true;
        }
        return fixed;
    }

    public boolean isTimeMachineBusy() {
        return isRunning || (timeMachineThread != null && timeMachineThread.isAlive());
    }

    public boolean isHealerBusy() {
        return isHealing || (healerThread != null && healerThread.isAlive());
    }

    /** Lancement par l'AutoPilot : remet à zéro l'offset des périodes à re-scanner, puis démarre. */
    public synchronized boolean startAutoSync(List<String> periods, Set<String> resetOffsets) {
        if (periods.isEmpty() || isTimeMachineBusy()) return false;
        for (String p : resetOffsets) saveState("offset_" + p, 0);
        startPeriodSync(periods);
        return true;
    }

    /** Insère une période en tête de file (ex: mois courant à rafraîchir pendant une passe). */
    public synchronized boolean injectPriority(String period, boolean forceFullRescan) {
        if (!isRunning || period.equals(currentPeriod) || periodQueue.contains(period)) return false;
        int off = getSavedState("offset_" + period), tot = getSavedState("total_" + period);
        if (forceFullRescan || (tot > 0 && off >= tot)) saveState("offset_" + period, 0);
        periodQueue.offerFirst(period);
        event(AUTOPILOT, INFO, period, (forceFullRescan ? "Collecte complète" : "Rafraîchissement")
                + " de " + period + " inséré en tête de file.");
        return true;
    }

    public long countLocalDistinct(String period) {
        return interventionRepository.countDistinctByPeriode(period);
    }

    /** Sonde légère (limit=1) : total réel Bytel d'une période. -1 si injoignable. */
    public int probeRemoteTotal(String period) {
        if (!throttle.acquire(() -> true)) return -1;
        long t0 = System.currentTimeMillis();
        try {
            ImportResponse resp = phpApiClient.triggerImport(0, 1, period);
            throttle.onSuccess(System.currentTimeMillis() - t0);
            if (resp != null && resp.isOk()) {
                int t = Math.max(0, resp.getTotalApi());
                if (t > 0) saveState("total_" + period, t);
                return t;
            }
            return -1;
        } catch (RestClientResponseException e) {
            handleHttpError(e);
            return -1;
        } catch (ResourceAccessException e) {
            handleTimeout();
            return -1;
        } catch (Exception e) {
            throttle.onError();
            return -1;
        }
    }

    /** EPS de la période non renvoyés par Bytel depuis sinceMillis. */
    public long countOrphans(String period, long sinceMillis) {
        return interventionRepository.countOrphans(period,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(sinceMillis), ZoneId.systemDefault()));
    }

    private String detailStatus(Intervention r) {
        String d = r.getDetailIntervention();
        if (d == null || d.isBlank() || "[]".equals(d)) return "NON_ENRICHI";
        if ("{}".equals(d)) {
            int a = r.getHealAttempts() == null ? 0 : r.getHealAttempts();
            return a >= healerMaxAttempts ? "DEFINITIF" : "EN_REESSAI";
        }
        return "OK";
    }

    /** Inspecteur d'un EPS : état en base + (option) vérification en direct chez Bytel, avec réparation auto. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectEps(String idIntervention, boolean live) {
        String id = idIntervention.trim();
        List<Intervention> rows = interventionRepository.findByIdIntervention(id);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id_intervention", id);
        res.put("found", !rows.isEmpty());
        res.put("duplicates", Math.max(0, rows.size() - 1));

        List<Map<String, Object>> list = new ArrayList<>();
        for (Intervention r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("periode", r.getPeriode());
            m.put("in_scope", scopeStart.isEmpty() || r.getPeriode() == null || r.getPeriode().compareTo(scopeStart) >= 0);
            m.put("etat", r.getEtat());
            m.put("type", r.getTypeIntervention());
            m.put("mainteneur", r.getMainteneur());
            m.put("source", r.getSourceIngestion());
            m.put("date_modification_etat", r.getDateModificationEtat() == null ? null : r.getDateModificationEtat().toString());
            m.put("last_seen_at", r.getLastSeenAt() == null ? null : r.getLastSeenAt().toString());
            m.put("detail_status", detailStatus(r));
            m.put("detail_size", r.getDetailIntervention() == null ? 0 : r.getDetailIntervention().length());
            m.put("heal_attempts", r.getHealAttempts() == null ? 0 : r.getHealAttempts());
            m.put("heal_next_at", r.getHealNextAt() == null ? null : r.getHealNextAt().toString());
            list.add(m);
        }
        res.put("rows", list);

        if (live) {
            Map<String, Object> lv = new LinkedHashMap<>();
            if (!throttle.acquire(() -> true)) {
                lv.put("reachable", false);
                lv.put("error", "Bytel en pause de protection");
            } else {
                long t0 = System.currentTimeMillis();
                try {
                    Map<String, Object> resp = phpApiClient.healData(List.of(id));
                    throttle.onSuccess(System.currentTimeMillis() - t0);
                    boolean ok = resp != null && Boolean.TRUE.equals(resp.get("ok"));
                    Object d = ok && resp.get("data") instanceof Map<?, ?> dm ? ((Map<String, Object>) dm).get(id) : null;
                    boolean has = hasDetail(d);
                    lv.put("reachable", ok);
                    lv.put("has_detail", has);
                    lv.put("latency_ms", System.currentTimeMillis() - t0);
                    if (has) {
                        String detail = d instanceof String str ? str : objectMapper.writeValueAsString(d);
                        lv.put("preview", detail.length() > 400 ? detail.substring(0, 400) + "…" : detail);
                        DetailFields df = DetailFields.parse(objectMapper, detail);
                        List<Object[]> fix = rows.stream().filter(r -> !"OK".equals(detailStatus(r)) || r.getExtractV() == null)
                                .map(r -> df.params(detail, r.getId())).toList();
                        if (!fix.isEmpty()) {
                            jdbcTemplate.batchUpdate(DetailFields.UPDATE_SQL, fix);
                            lv.put("repaired", fix.size());
                            event(HEALER, SUCCESS, rows.get(0).getPeriode(), "EPS " + id + " réparé par vérification manuelle (détail récupéré en direct).");
                        }
                    }
                } catch (RestClientResponseException e) {
                    handleHttpError(e);
                    lv.put("reachable", false);
                    lv.put("error", "HTTP " + e.getStatusCode().value());
                } catch (ResourceAccessException e) {
                    handleTimeout();
                    lv.put("reachable", false);
                    lv.put("error", "Timeout Bytel");
                } catch (Exception e) {
                    throttle.onError();
                    lv.put("reachable", false);
                    lv.put("error", e.getMessage());
                }
            }
            res.put("live", lv);
        }

        String verdict;
        if (rows.isEmpty()) verdict = "ABSENT_BASE";
        else if (rows.size() > 1) verdict = "DOUBLON";
        else if ("OK".equals(detailStatus(rows.get(0))) || (res.get("live") instanceof Map<?, ?> l && l.get("repaired") != null)) verdict = "OK";
        else verdict = detailStatus(rows.get(0));
        res.put("verdict", verdict);
        return res;
    }

    public int getSavedOffset(String period) { return getSavedState("offset_" + period); }
    public int getSavedTotal(String period) { return getSavedState("total_" + period); }

    private void handleHttpError(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        boolean wasPaused = throttle.isPaused();
        if (code == 403 || body.contains("Access Denied")) {
            throttle.onBlocked();
        } else if (code == 429 || code >= 500 || body.contains("Timeout")) {
            throttle.onOverload("HTTP " + code);
        } else {
            throttle.onError();
        }
        if (!wasPaused && throttle.isPaused()) {
            event(BYTEL, ERROR, null, "Protection activée : " + throttle.describe());
        }
    }

    private void handleTimeout() {
        boolean wasPaused = throttle.isPaused();
        throttle.onOverload("timeout/IO");
        if (!wasPaused && throttle.isPaused()) {
            event(BYTEL, ERROR, null, "Protection activée : " + throttle.describe());
        }
    }

    // ------------------------------------------------------------ Maintenance

    public synchronized int fixPeriodsForMonth(String targetPeriod) {
        event(MAINTENANCE, INFO, targetPeriod, "Scan de " + targetPeriod + " pour trouver les EPS intrus...");

        List<Intervention> interventions = interventionRepository.findByPeriode(targetPeriod);
        int fixedCount = 0;
        List<Intervention> toUpdate = new ArrayList<>();

        for (Intervention inv : interventions) {
            if (inv.getDetailIntervention() != null && !inv.getDetailIntervention().equals("{}")) {
                try {
                    JsonNode root = objectMapper.readTree(inv.getDetailIntervention());
                    if (root.has("periode")) {
                        String realPeriodRaw = root.get("periode").asText("");
                        if (!realPeriodRaw.isEmpty()) {
                            String realPeriod = realPeriodRaw.replace("-", "_");
                            if (!realPeriod.equals(targetPeriod)) {
                                inv.setPeriode(realPeriod);
                                toUpdate.add(inv);
                                fixedCount++;
                            }
                        }
                    }
                } catch (Exception e) {
                    // JSON invalide : ignoré
                }
            }
        }

        if (!toUpdate.isEmpty()) interventionRepository.saveAll(toUpdate);

        event(MAINTENANCE, SUCCESS, targetPeriod, fixedCount + " EPS intrus trouvés et déplacés depuis " + targetPeriod);
        return fixedCount;
    }

    public synchronized void startPeriodSync(List<String> periods) {
        if (isRunning || (timeMachineThread != null && timeMachineThread.isAlive())) {
            log.warn("Tentative de démarrage bloquée : un processus est déjà en cours.");
            return;
        }

        periodQueue.clear();
        periodQueue.addAll(periods);
        currentPeriod = periodQueue.poll();
        totalPeriodProcessed = 0;
        resetPeriodCounters();

        int savedOffset = getSavedState("offset_" + currentPeriod);
        if (savedOffset > 0) {
            event(TM, INFO, currentPeriod, "Reprise de " + currentPeriod + " à l'offset " + savedOffset
                    + " (" + periods.size() + " période(s) en file)");
        } else {
            event(TM, INFO, currentPeriod, "Démarrage de " + currentPeriod + " (" + periods.size() + " période(s) en file)");
        }

        isRunning = true;
        currentEta = "Initialisation...";
        timeMachineStatus = "Démarrage en cours";
        syncStartTime = System.currentTimeMillis();
        totalProcessedSinceStart = 0;

        timeMachineThread = new Thread(this::timeMachineLoop, "time-machine");
        timeMachineThread.start();
    }

    public synchronized void rescanPeriod(String period) {
        if (isRunning) {
            event(TM, WARN, period, "Stoppez la synchronisation en cours avant de lancer un Rescan.");
            return;
        }
        event(TM, INFO, period, "Smart Rescan de " + period + " (offset remis à 0)");
        saveState("offset_" + period, 0);
        startPeriodSync(Collections.singletonList(period));
    }

    public synchronized void stopSync() {
        isRunning = false;
        currentEta = "Arrêté";
        timeMachineStatus = "Arrêt demandé";
        periodQueue.clear();
        event(TM, WARN, currentPeriod, "Arrêt de la Time Machine demandé.");
    }

    public synchronized void startHealer(String mode) {
        if (isHealing || (healerThread != null && healerThread.isAlive())) {
            log.warn("Healer déjà en cours d'exécution.");
            return;
        }
        isHealing = true;
        healerMode = mode;
        healerStatus = "Démarrage en cours";
        healerStartedAt = System.currentTimeMillis();
        event(HEALER, INFO, null, "Démarrage du Healer en mode " + mode);

        healerThread = new Thread(this::backgroundHealerLoop, "healer");
        healerThread.start();
    }

    public synchronized void stopHealer() {
        isHealing = false;
        healerStatus = "Arrêt demandé";
        event(HEALER, WARN, null, "Arrêt du Healer demandé.");
    }

    public synchronized void purgeDatabase() {
        stopSync();
        stopHealer();
        sleep(2000);

        try { phpApiClient.resetIonos(); } catch (Exception e) { log.error("Erreur reset IONOS", e); }

        interventionRepository.truncateInterventions();
        // On garde le flag AutoPilot (ap_enabled) mais on remet tout le reste à zéro.
        syncStateRepository.findAll().stream()
                .filter(s -> !"ap_enabled".equals(s.getStateKey()))
                .forEach(syncStateRepository::delete);

        totalHealerProcessed = 0;
        totalPeriodProcessed = 0;
        currentPeriod = null;
        periodQueue.clear();

        event(MAINTENANCE, WARN, null, "PURGE TOTALE de la base effectuée.");
    }

    public synchronized int purgePeriod(String period) {
        String cleanPeriod = period.replace("_", "-");

        interventionRepository.deleteLogsByPeriodNative(cleanPeriod, period);
        int deletedCount = interventionRepository.deleteInterventionsByPeriodNative(cleanPeriod, period);

        syncStateRepository.deleteById("offset_" + period);
        syncStateRepository.deleteById("total_" + period);

        event(MAINTENANCE, WARN, period, "Période " + period + " purgée (" + deletedCount + " supprimées).");
        return deletedCount;
    }

    public void cleanDuplicatesTask() {
        event(MAINTENANCE, INFO, null, "Smart Clean (dédoublonnage) lancé.");
        int totalDeleted = 0;
        while (true) {
            List<Long> duplicateIds = interventionRepository.findDuplicateIds();
            if (duplicateIds.isEmpty()) break;
            interventionRepository.deleteLogsByIds(duplicateIds);
            totalDeleted += interventionRepository.deleteInterventionsByIds(duplicateIds);
        }
        event(MAINTENANCE, SUCCESS, null, "Smart Clean terminé : " + totalDeleted + " doublons supprimés.");
    }

    public int retryFailedHeals() {
        int count = interventionRepository.resetFailedHeals();
        queueCachedAt = 0;
        event(HEALER, INFO, null, count + " EPS fantômes : compteur d'essais remis à zéro (ré-essai immédiat, étalé par lots).");
        return count;
    }

    // ------------------------------------------------------------ Time Machine

    private void resetPeriodCounters() {
        periodInserted = 0;
        periodUpdated = 0;
        periodChanged = 0;
        scanStartMarkedFor = null;
        periodStartedAt = System.currentTimeMillis();
    }

    private void finishPeriod(String period, int remoteTotal, boolean empty) {
        long local = countLocalDistinct(period);
        long duration = System.currentTimeMillis() - periodStartedAt;
        long scanStart = getStateLong("ap_scanstart_" + period);
        long orphans = scanStart > 0 ? countOrphans(period, scanStart) : -1;
        PeriodResult res = new PeriodResult(period, periodInserted, periodUpdated, remoteTotal, local, duration, empty, periodChanged, orphans);

        if (empty) {
            event(TM, WARN, period, "Bytel ne retourne aucun EPS pour " + period + ".");
        } else {
            event(TM, SUCCESS, period, String.format(
                    "Collecte %s terminée en %s : %d nouveaux, %d réellement modifiés (%d relus). Base: %d / Bytel: %d EPS%s",
                    period, formatDuration(duration), periodInserted, periodChanged, periodUpdated, local, remoteTotal,
                    orphans > 0 ? " · " + orphans + " EPS plus renvoyés par Bytel" : ""));
            if (remoteTotal > 0 && local < remoteTotal) {
                event(TM, WARN, period, (remoteTotal - local) + " EPS manquants sur " + period
                        + " (" + local + "/" + remoteTotal + ") — la vérification va relancer une collecte.");
            }
        }
        try { periodListener.accept(res); } catch (Exception e) { log.warn("Listener période: {}", e.getMessage()); }
    }

    private void timeMachineLoop() {
        timeMachineStatus = "En cours d'aspiration";

        while (isRunning && currentPeriod != null) {
            try {
                final String activePeriod = currentPeriod;
                int localOffset = getSavedState("offset_" + activePeriod);
                int localTotalApi = getSavedState("total_" + activePeriod);
                boolean periodEmpty = false;

                // Début d'une collecte complète : on note l'heure (sert au contrôle des EPS orphelins).
                if (localOffset == 0 && !activePeriod.equals(scanStartMarkedFor)) {
                    saveStateStr("ap_scanstart_" + activePeriod, Long.toString(System.currentTimeMillis()));
                    scanStartMarkedFor = activePeriod;
                }

                if (localTotalApi > 0 && localOffset >= localTotalApi) {
                    finishPeriod(activePeriod, localTotalApi, false);
                    if (!advanceToNextPeriod()) break;
                    continue;
                }

                timeMachineStatus = "Scan [" + activePeriod + "] en cours...";

                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        if (!throttle.acquire(() -> isRunning)) break;
                        long t0 = System.currentTimeMillis();
                        ImportResponse importResp;
                        try {
                            importResp = phpApiClient.triggerImport(localOffset, TIME_MACHINE_BATCH, activePeriod);
                        } catch (RestClientResponseException ex) {
                            handleHttpError(ex);
                            throw ex;
                        } catch (ResourceAccessException ex) {
                            handleTimeout();
                            throw ex;
                        } catch (RuntimeException ex) {
                            throttle.onError();
                            throw ex;
                        }
                        throttle.onSuccess(System.currentTimeMillis() - t0);

                        if (importResp != null && importResp.isOk()) {

                            if (importResp.getBatchCount() == 0) {
                                if (importResp.getTotalApi() <= 0 && localOffset == 0) {
                                    periodEmpty = true;
                                    importSuccess = true;
                                    break;
                                }
                                // Page vide en plein milieu : on avance pour ne pas boucler.
                                localOffset += TIME_MACHINE_BATCH;
                                saveState("offset_" + activePeriod, localOffset);
                                if (importResp.getTotalApi() > 0) {
                                    localTotalApi = importResp.getTotalApi();
                                    saveState("total_" + activePeriod, localTotalApi);
                                }
                                importSuccess = true;
                                break;
                            }

                            int newOffset = importResp.getNextOffset();
                            if (newOffset > 0 && newOffset <= localOffset) {
                                throw new RuntimeException("Glitch API Bouygues (offset retourné " + newOffset + "), retry de la page " + localOffset);
                            }

                            List<Intervention> incomingData = importResp.getData();
                            if (incomingData != null && !incomingData.isEmpty()) {
                                String expectedPeriod = activePeriod.replace("_", "-");
                                String firstRecordPeriod = incomingData.get(0).getPeriode();
                                if (firstRecordPeriod != null && !firstRecordPeriod.equals(expectedPeriod)) {
                                    throw new RuntimeException("Glitch API Bouygues : EPS de " + firstRecordPeriod + " reçu au lieu de " + expectedPeriod + ", retry de la page");
                                }

                                int[] counts = upsertBatch(incomingData, activePeriod);
                                periodInserted += counts[0];
                                periodUpdated += counts[1];
                                periodChanged += counts[2];
                                feed.log("TM", "OK", String.format("[%s] page %d→%d · %d EPS reçus · %s%d nouveaux · %d modifiés · contrôle base ✓ · %d ms",
                                        activePeriod, localOffset, newOffset, incomingData.size(), counts[0] > 0 ? "+" : "", counts[0], counts[2],
                                        System.currentTimeMillis() - t0));
                            }

                            int newTotal = importResp.getTotalApi();
                            if (localTotalApi == 0 || (newTotal > 0 && Math.abs(newTotal - localTotalApi) < 10000)) {
                                localTotalApi = newTotal;
                            }

                            localOffset = newOffset;
                            saveState("offset_" + activePeriod, localOffset);
                            saveState("total_" + activePeriod, localTotalApi);
                            totalPeriodProcessed += importResp.getBatchCount();
                            totalProcessedSinceStart += importResp.getBatchCount();

                            if (totalProcessedSinceStart > 0 && syncStartTime > 0) {
                                long elapsedMillis = System.currentTimeMillis() - syncStartTime;
                                long millisPerItem = elapsedMillis / totalProcessedSinceStart;
                                int remainingItems = Math.max(0, localTotalApi - localOffset);
                                currentEta = formatDuration(remainingItems * millisPerItem);
                            }
                            importSuccess = true;
                            timeMachineStatus = "[" + activePeriod + "] " + localOffset + "/" + localTotalApi + " | " + throttle.describe();
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        int code = e.getStatusCode().value();
                        String body = e.getResponseBodyAsString();
                        if (code == 404) {
                            event(TM, ERROR, activePeriod, "Erreur 404 : endpoint PHP introuvable.");
                            timeMachineStatus = "Erreur HTTP 404";
                            sleep(15000);
                        } else if (code == 403 || body.contains("Access Denied")) {
                            event(TM, ERROR, activePeriod, "Bloqué par le pare-feu Bouygues (Akamai). Pause globale.");
                            timeMachineStatus = "Banni - " + throttle.describe();
                        } else if (code == 429 || code >= 500 || body.contains("Timeout")) {
                            feed.log("TM", "ERROR", "[" + activePeriod + "] HTTP " + code + " à l'offset " + localOffset + " (essai " + attempt + "/3) · "
                                    + abbreviate(body) + " · " + throttle.describe());
                            if (attempt == 1) event(TM, WARN, activePeriod, "Serveur Bouygues surchargé (HTTP " + code + "), ralentissement auto. Offset " + localOffset);
                            timeMachineStatus = "Surcharge - " + throttle.describe();
                        } else {
                            event(TM, ERROR, activePeriod, "Erreur HTTP " + code);
                            timeMachineStatus = "Erreur HTTP " + code;
                            sleep(10000);
                        }
                    } catch (ResourceAccessException e) {
                        feed.log("TM", "ERROR", "[" + activePeriod + "] Timeout/IO à l'offset " + localOffset + " (essai " + attempt + "/3) · " + abbreviate(e.getMessage()));
                        event(TM, WARN, activePeriod, "Timeout Bytel à l'offset " + localOffset + ", ralentissement auto.");
                        timeMachineStatus = "Timeout - " + throttle.describe();
                    } catch (Exception e) {
                        log.error("Erreur inattendue", e);
                        timeMachineStatus = "Glitch Bouygues - Retry";
                        feed.log("TM", "ERROR", "[" + activePeriod + "] offset " + localOffset + " (essai " + attempt + "/3) · " + abbreviate(e.getMessage()));
                        event(TM, WARN, activePeriod, e.getMessage());
                        sleep(2000);
                    }
                }

                if (periodEmpty) {
                    finishPeriod(activePeriod, 0, true);
                    if (!advanceToNextPeriod()) break;
                    continue;
                }

                if (!importSuccess && isRunning) {
                    timeMachineStatus = "Échecs répétés, pause 30s";
                    event(TM, ERROR, activePeriod, "3 échecs d'affilée à l'offset " + localOffset + ", pause 30s.");
                    sleep(30000);
                }

            } catch (Exception e) {
                log.error("Exception critique dans la boucle Time Machine", e);
                timeMachineStatus = "Erreur Critique";
                event(TM, ERROR, currentPeriod, "Exception critique : " + e.getMessage());
                sleep(5000);
            }
        }
        timeMachineStatus = isRunning ? "File terminée" : "Arrêté";
        isRunning = false;
        log.info("Thread Time Machine arrêté.");
    }

    /** Passe à la période suivante. false s'il n'y en a plus. */
    private boolean advanceToNextPeriod() {
        currentPeriod = periodQueue.poll();
        if (currentPeriod == null) {
            event(TM, SUCCESS, null, "File terminée : toutes les périodes ont été traitées.");
            currentEta = "Terminé";
            return false;
        }
        event(TM, INFO, currentPeriod, "Passage à " + currentPeriod);
        resetPeriodCounters();
        totalProcessedSinceStart = 0;
        syncStartTime = System.currentTimeMillis();
        return true;
    }

    /**
     * Upsert d'un lot + contrôle. Retourne {insérés, relus, réellement modifiés}.
     * Après écriture, on vérifie que CHAQUE EPS reçu est bien en base (sinon la page est rejouée)
     * et on marque last_seen_at (sert à détecter les EPS que Bytel ne renvoie plus).
     */
    private int[] upsertBatch(List<Intervention> incomingData, String activePeriod) {
        int[] counts = new int[3];
        LocalDateTime seenAt = LocalDateTime.now();
        List<String> incomingEpsIds = incomingData.stream().map(Intervention::getIdIntervention)
                .filter(id -> id != null && !id.isEmpty()).distinct().toList();

        transactionTemplate.executeWithoutResult(status -> {
            Map<String, Intervention> existingMap = interventionRepository.findByIdInterventionIn(incomingEpsIds).stream()
                    .collect(Collectors.toMap(Intervention::getIdIntervention, i -> i, (i1, i2) -> i1));
            List<Intervention> toSave = new ArrayList<>();

            for (Intervention incoming : incomingData) {
                if (incoming.getIdIntervention() == null || incoming.getIdIntervention().isEmpty()) continue;
                Intervention existing = existingMap.get(incoming.getIdIntervention());
                String realPeriod = incoming.getPeriode() != null ? incoming.getPeriode().replace("-", "_") : activePeriod;

                if (existing == null) {
                    existing = incoming;
                    existing.setId(null);
                    existing.setSourceIngestion("TIME_MACHINE");
                    existing.setPeriode(realPeriod);
                    existing.setLastSeenAt(seenAt);
                    existingMap.put(existing.getIdIntervention(), existing);
                    toSave.add(existing);
                    counts[0]++;
                } else {
                    boolean changed = !Objects.equals(existing.getEtat(), incoming.getEtat())
                            || !Objects.equals(existing.getDateModificationEtat(), incoming.getDateModificationEtat())
                            || !Objects.equals(existing.getTypeIntervention(), incoming.getTypeIntervention())
                            || !Objects.equals(existing.getMainteneur(), incoming.getMainteneur())
                            || !Objects.equals(existing.getPeriode(), realPeriod);
                    boolean payloadChanged = !Objects.equals(existing.getPayloadRecu(), incoming.getPayloadRecu());
                    if (changed || payloadChanged) {
                        existing.setEtat(incoming.getEtat());
                        existing.setDateModificationEtat(incoming.getDateModificationEtat());
                        existing.setTypeIntervention(incoming.getTypeIntervention());
                        existing.setMainteneur(incoming.getMainteneur());
                        existing.setPayloadRecu(incoming.getPayloadRecu());
                        existing.setPeriode(realPeriod);
                        toSave.add(existing);
                    }
                    if (changed) counts[2]++;
                    counts[1]++;
                }
            }
            if (!toSave.isEmpty()) interventionRepository.saveAll(toSave);
        });

        if (!incomingEpsIds.isEmpty()) {
            MapSqlParameterSource ps = new MapSqlParameterSource()
                    .addValue("ts", Timestamp.valueOf(seenAt))
                    .addValue("ids", incomingEpsIds);
            named().update("UPDATE interventions SET last_seen_at = :ts WHERE id_intervention IN (:ids)", ps);
            Integer present = named().queryForObject(
                    "SELECT COUNT(DISTINCT id_intervention) FROM interventions WHERE id_intervention IN (:ids)", ps, Integer.class);
            if (present == null || present < incomingEpsIds.size()) {
                throw new IllegalStateException("Contrôle page : " + (incomingEpsIds.size() - (present == null ? 0 : present))
                        + " EPS reçus introuvables en base après écriture — page rejouée");
            }
        }
        return counts;
    }

    // ------------------------------------------------------------ Healer

    /** Espacement des ré-essais d'un EPS sans détail (minutes) : 30 min, 2 h, 8 h, 24 h, 48 h… */
    private static final long[] HEAL_BACKOFF_MIN = {30, 120, 480, 1440, 2880, 5760};

    private volatile Map<String, Object> queueCache = Map.of();
    private volatile long queueCachedAt = 0;

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    /** File du Healer dans le périmètre (cache 10 s). */
    public Map<String, Object> healQueue() {
        if (System.currentTimeMillis() - queueCachedAt > 10_000) {
            List<Object[]> r = interventionRepository.healQueueBreakdown(scopeStart, healerMaxAttempts);
            Object[] row = r.isEmpty() ? new Object[4] : r.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fresh", num(row[0]));
            m.put("retry_due", num(row[1]));
            m.put("backoff", num(row[2]));
            m.put("definitive", num(row[3]));
            queueCache = m;
            queueCachedAt = System.currentTimeMillis();
        }
        return queueCache;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private void backgroundHealerLoop() {
        healerStatus = "En veille";
        boolean announcedIdle = false;

        while (isHealing) {
            try {
                String scope = scopeStart;
                List<Intervention> chunk = new ArrayList<>("TIME_MACHINE".equals(healerMode)
                        ? interventionRepository.findFreshAsc(scope, healerChunkSize)
                        : interventionRepository.findFreshDesc(scope, healerChunkSize));
                int freshCount = chunk.size();
                if (chunk.size() < healerChunkSize) {
                    chunk.addAll(interventionRepository.findRetryDue(scope, healerMaxAttempts, healerChunkSize - chunk.size()));
                }

                queueCachedAt = 0;
                Map<String, Object> q = healQueue();
                long pending = num(q.get("fresh")) + num(q.get("retry_due"));

                if (chunk.isEmpty()) {
                    healTotal = 0;
                    healCurrent = 0;
                    long backoff = num(q.get("backoff"));
                    healerStatus = backoff > 0
                            ? "À jour — " + backoff + " EPS attendent un ré-essai programmé"
                            : "Périmètre 100% traité";
                    if (!announcedIdle) {
                        event(HEALER, SUCCESS, null, backoff > 0
                                ? "File vide. " + backoff + " EPS sans détail seront ré-essayés plus tard (espacement), "
                                  + q.get("definitive") + " classés définitifs."
                                : "Tous les EPS du périmètre ont été traités (" + q.get("definitive") + " définitifs sans détail).");
                        announcedIdle = true;
                    }
                    sleep(20000);
                    continue;
                }
                announcedIdle = false;

                healTotal = (int) pending;
                healCurrent = 0;
                healerStatus = "Enrichissement : " + freshCount + " nouveaux + " + (chunk.size() - freshCount)
                        + " ré-essais | " + throttle.describe();
                long t0 = System.currentTimeMillis();

                List<Future<int[]>> futures = new ArrayList<>();
                for (List<Intervention> batch : partition(chunk, Math.max(1, healerBatchSize))) {
                    futures.add(healerThreadPool.submit(() -> healBatch(batch)));
                }
                int ok = 0, notFound = 0, failed = 0, definitive = 0, recovered = 0;
                for (Future<int[]> f : futures) {
                    try {
                        int[] r = f.get();
                        ok += r[0]; notFound += r[1]; failed += r[2]; definitive += r[3]; recovered += r[4];
                    } catch (ExecutionException ee) {
                        log.error("Erreur batch Healer", ee.getCause());
                        lastHealError = String.valueOf(ee.getCause().getMessage());
                    }
                }
                int done = ok + notFound + failed;
                long dur = System.currentTimeMillis() - t0;

                healCurrent += done;
                totalHealerProcessed += done;
                if (done > 0) healWindow.add(new long[]{System.currentTimeMillis(), ok});
                recordLot(chunk.size(), ok, notFound, failed, dur, pending);

                healerStatus = "Lot " + done + "/" + chunk.size() + " traité | " + throttle.describe();
                if (done > 0) {
                    event(HEALER, failed > 0 ? WARN : SUCCESS, null, String.format(
                            "Lot de %d EPS (%d nouveaux, %d ré-essais) en %s : %d enrichis%s, %d sans détail → ré-essai programmé, %d échecs réseau%s. File : %d.",
                            done, freshCount, chunk.size() - freshCount, formatDuration(dur), ok,
                            recovered > 0 ? " (dont " + recovered + " au 2e essai)" : "",
                            notFound, failed, definitive > 0 ? ", " + definitive + " classés définitifs" : "",
                            Math.max(0, pending - done)));
                }

            } catch (Exception e) {
                log.error("Exception critique Healer", e);
                healerStatus = "Erreur Critique";
                lastHealError = e.getMessage();
                event(HEALER, ERROR, null, "Exception critique : " + e.getMessage());
                sleep(10000);
            }
        }
        healerStatus = "Arrêté";
        log.info("Thread Healer arrêté.");
    }

    private void recordLot(int size, int ok, int notFound, int failed, long durationMs, long queue) {
        Map<String, Object> lot = new LinkedHashMap<>();
        lot.put("at", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        lot.put("size", size);
        lot.put("ok", ok);
        lot.put("not_found", notFound);
        lot.put("failed", failed);
        lot.put("duration_ms", durationMs);
        lot.put("queue", queue);
        recentLots.add(0, lot);
        while (recentLots.size() > 40) recentLots.remove(recentLots.size() - 1);
    }

    /**
     * Soigne un petit lot d'EPS. Retourne {enrichis, sans détail, échecs réseau, définitifs, sauvés au 2e essai}.
     * Un EPS sans détail n'est PAS remis en file tout de suite : il reçoit un ré-essai programmé (backoff),
     * et après {@code healerMaxAttempts} essais il est classé définitif -> le Healer termine toujours.
     */
    @SuppressWarnings("unchecked")
    private int[] healBatch(List<Intervention> batch) {
        if (!isHealing) return new int[5];
        List<String> idsToHeal = batch.stream().map(Intervention::getIdIntervention).toList();
        Map<String, Object> healedData = null;

        for (int attempt = 1; attempt <= 3 && isHealing; attempt++) {
            if (!throttle.acquire(() -> isHealing)) return new int[5];
            long t0 = System.currentTimeMillis();
            try {
                Map<String, Object> response = phpApiClient.healData(idsToHeal);
                throttle.onSuccess(System.currentTimeMillis() - t0);
                if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                    Object rawData = response.get("data");
                    healedData = rawData instanceof Map ? (Map<String, Object>) rawData : new HashMap<>();
                    break;
                }
                lastHealError = "Réponse PHP sans ok=true";
            } catch (RestClientResponseException e) {
                handleHttpError(e);
                lastHealError = "HTTP " + e.getStatusCode().value();
                feed.log("HEALER", "ERROR", "Lot " + idsToHeal.get(0) + "… (" + idsToHeal.size() + " EPS) · HTTP " + e.getStatusCode().value()
                        + " (essai " + attempt + "/3) · " + abbreviate(e.getResponseBodyAsString()));
            } catch (ResourceAccessException e) {
                handleTimeout();
                lastHealError = "Timeout Bytel";
                feed.log("HEALER", "ERROR", "Lot " + idsToHeal.get(0) + "… · Timeout/IO (essai " + attempt + "/3) · " + abbreviate(e.getMessage()));
            } catch (Exception e) {
                throttle.onError();
                lastHealError = e.getMessage();
                feed.log("HEALER", "ERROR", "Lot " + idsToHeal.get(0) + "… · " + abbreviate(e.getMessage()));
            }
        }

        if (healedData == null && !isHealing) return new int[5];
        long tBatch = System.currentTimeMillis(); // arrêt : on ne touche à rien

        // 2e chance immédiate pour les IDs omis par le proxy.
        int recovered = 0;
        if (healedData != null && isHealing) {
            final Map<String, Object> first = healedData;
            List<String> missing = idsToHeal.stream().filter(id -> !hasDetail(first.get(id))).toList();
            if (!missing.isEmpty() && throttle.acquire(() -> isHealing)) {
                long t0 = System.currentTimeMillis();
                try {
                    Map<String, Object> retry = phpApiClient.healData(missing);
                    throttle.onSuccess(System.currentTimeMillis() - t0);
                    if (retry != null && Boolean.TRUE.equals(retry.get("ok")) && retry.get("data") instanceof Map<?, ?> rd) {
                        Map<String, Object> merged = new HashMap<>(first);
                        for (String id : missing) {
                            Object d = rd.get(id);
                            if (hasDetail(d)) { merged.put(id, d); recovered++; }
                        }
                        healedData = merged;
                    }
                } catch (RestClientResponseException e) {
                    handleHttpError(e);
                } catch (ResourceAccessException e) {
                    handleTimeout();
                } catch (Exception e) {
                    throttle.onError();
                }
            }
        }

        // Signal d'alerte avant ban Akamai : Bytel répond "ok" mais renvoie un lot 100% vide, plusieurs fois de suite.
        // -> on freine fort tout de suite, et ces EPS sont reprogrammés dans 10 min SANS consommer d'essai.
        if (healedData != null && batch.size() >= 3) {
            final Map<String, Object> hd = healedData;
            boolean allEmpty = idsToHeal.stream().noneMatch(id -> hasDetail(hd.get(id)));
            if (!allEmpty) {
                emptyBatchStreak.set(0);
            } else {
                int streak = emptyBatchStreak.incrementAndGet();
                if (streak >= 2 && streak <= 20) {
                    throttle.onBanWarning(streak);
                    Timestamp later = Timestamp.valueOf(LocalDateTime.now().plusMinutes(10));
                    List<Object[]> park = batch.stream().map(inv -> new Object[]{new SqlParameterValue(Types.TIMESTAMP, later), inv.getId()}).toList();
                    jdbcTemplate.batchUpdate("UPDATE interventions SET detail_intervention = '{}', heal_attempts = COALESCE(heal_attempts, 0), heal_next_at = ? WHERE id = ?", park);
                    feed.log("HEALER", "WARN", batch.size() + " EPS (" + idsToHeal.get(0) + "…) · lot 100% vide (" + streak
                            + "e d'affilée) → Bytel sature : on ralentit, ré-essai dans 10 min sans compter d'essai · " + throttle.describe());
                    hFailed.addAndGet(batch.size());
                    return new int[]{0, 0, batch.size(), 0, recovered};
                }
            }
        }

        int ok = 0, notFound = 0, failed = 0, definitive = 0;
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> okUpdates = new ArrayList<>();
        List<Object[]> koUpdates = new ArrayList<>();
        for (Intervention inv : batch) {
            Object d = healedData == null ? null : healedData.get(inv.getIdIntervention());
            String detail = null;
            if (hasDetail(d)) {
                try { detail = d instanceof String str ? str : objectMapper.writeValueAsString(d); }
                catch (Exception ignored) { detail = null; }
            }
            if (detail != null) {
                ok++;
                okUpdates.add(DetailFields.parse(objectMapper, detail).params(detail, inv.getId()));
            } else {
                if (healedData == null) failed++; else notFound++;
                int attempts = (inv.getHealAttempts() == null ? 0 : inv.getHealAttempts()) + 1;
                Timestamp next = null;
                if (attempts >= healerMaxAttempts) definitive++;
                else next = Timestamp.valueOf(now.plusMinutes(HEAL_BACKOFF_MIN[Math.min(attempts - 1, HEAL_BACKOFF_MIN.length - 1)]));
                koUpdates.add(new Object[]{attempts, new SqlParameterValue(Types.TIMESTAMP, next), inv.getId()});
            }
        }
        if (!okUpdates.isEmpty()) {
            jdbcTemplate.batchUpdate(DetailFields.UPDATE_SQL, okUpdates);
        }
        if (!koUpdates.isEmpty()) {
            jdbcTemplate.batchUpdate("UPDATE interventions SET detail_intervention = '{}', heal_attempts = ?, heal_next_at = ? WHERE id = ?", koUpdates);
        }

        // Beaucoup d'IDs omis alors que Bytel répond : symptôme de surcharge -> on ralentit un peu.
        if (healedData != null && notFound * 2 > batch.size()) throttle.onSoftPressure();

        hEnriched.addAndGet(ok);
        hNotFound.addAndGet(notFound);
        hFailed.addAndGet(failed);
        hRecovered.addAndGet(recovered);
        hDefinitive.addAndGet(definitive);
        if (failed > 0) hFailedBatches.incrementAndGet();
        if (ok > 0) lastHealSuccessAt = System.currentTimeMillis();
        feed.log("HEALER", failed > 0 ? "ERROR" : notFound > 0 ? "WARN" : "OK", String.format(
                "%d EPS (%s…) · %d détails ✓%s%s%s%s",
                batch.size(), idsToHeal.get(0), ok,
                recovered > 0 ? " (dont " + recovered + " au 2e essai)" : "",
                notFound > 0 ? " · " + notFound + " sans détail → ré-essai programmé" : "",
                failed > 0 ? " · " + failed + " échecs réseau" : "",
                definitive > 0 ? " · " + definitive + " définitifs" : ""));
        return new int[]{ok, notFound, failed, definitive, recovered};
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static boolean hasDetail(Object d) {
        if (d == null) return false;
        if (d instanceof String str) return !str.isBlank() && !"{}".equals(str) && !"[]".equals(str);
        if (d instanceof Map<?, ?> m) return !m.isEmpty();
        if (d instanceof Collection<?> c) return !c.isEmpty();
        return true;
    }

    /** Vue détaillée du Healer pour l'UI. */
    public Map<String, Object> healerInsights() {
        long now = System.currentTimeMillis();
        while (!healWindow.isEmpty() && now - healWindow.peekFirst()[0] > 10 * 60_000) healWindow.pollFirst();
        long last5 = healWindow.stream().filter(w -> now - w[0] <= 5 * 60_000).mapToLong(w -> w[1]).sum();
        Map<String, Object> q = healQueue();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", isHealing);
        m.put("mode", healerMode);
        m.put("status", healerStatus);
        m.put("scope_start", scopeStart);
        m.put("max_attempts", healerMaxAttempts);
        m.put("queue", num(q.get("fresh")) + num(q.get("retry_due")));
        m.put("fresh", num(q.get("fresh")));
        m.put("retry_due", num(q.get("retry_due")));
        m.put("backoff", num(q.get("backoff")));
        m.put("definitive", num(q.get("definitive")));
        m.put("phantoms", num(q.get("retry_due")) + num(q.get("backoff")) + num(q.get("definitive")));
        m.put("enriched", hEnriched.get());
        m.put("not_found", hNotFound.get());
        m.put("failed", hFailed.get());
        m.put("failed_batches", hFailedBatches.get());
        m.put("recovered", hRecovered.get());
        m.put("definitive_session", hDefinitive.get());
        m.put("rate_per_min", Math.round(last5 / 5.0));
        m.put("last_success_ago_s", lastHealSuccessAt == 0 ? -1 : (now - lastHealSuccessAt) / 1000);
        m.put("last_error", lastHealError);
        m.put("uptime_s", healerStartedAt == 0 || !isHealing ? 0 : (now - healerStartedAt) / 1000);
        m.put("recent_lots", recentLots);
        m.put("bytel_paused", throttle.isPaused());
        m.put("throttle", throttle.describe());
        return m;
    }

    // ------------------------------------------------------------ Utils

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
    }

    static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    private void saveState(String key, int value) {
        SyncState state = syncStateRepository.findById(key).orElse(new SyncState());
        state.setStateKey(key);
        state.setStateValue(value);
        syncStateRepository.save(state);
    }

    private void saveStateStr(String key, String value) {
        SyncState state = syncStateRepository.findById(key).orElse(new SyncState());
        state.setStateKey(key);
        state.setStateValueStr(value);
        syncStateRepository.save(state);
    }

    private long getStateLong(String key) {
        String v = syncStateRepository.findById(key).map(SyncState::getStateValueStr).orElse(null);
        if (v == null || v.isEmpty()) return 0L;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0L; }
    }

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).filter(Objects::nonNull).orElse(0);
    }
}
