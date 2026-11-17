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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalTime;
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
                               long localDistinct, long durationMs, boolean empty) {}

    private final PhpApiClient phpApiClient;
    private final InterventionRepository interventionRepository;
    private final SyncStateRepository syncStateRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final BytelThrottle throttle;
    private final EventLogService events;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kyntus.healer.chunk-size:200}")
    private int healerChunkSize;

    @Value("${kyntus.healer.batch-size:10}")
    private int healerBatchSize;

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
    private volatile long periodStartedAt = 0;

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
    public synchronized boolean injectPriority(String period) {
        if (!isRunning || period.equals(currentPeriod) || periodQueue.contains(period)) return false;
        int off = getSavedState("offset_" + period), tot = getSavedState("total_" + period);
        if (tot > 0 && off >= tot) saveState("offset_" + period, 0);
        periodQueue.offerFirst(period);
        event(AUTOPILOT, INFO, period, "Rafraîchissement prioritaire de " + period + " inséré en tête de file.");
        return true;
    }

    public long countLocalDistinct(String period) {
        return interventionRepository.countDistinctByPeriode(period);
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
        event(HEALER, INFO, null, count + " EPS fantômes remis en file d'attente.");
        return count;
    }

    // ------------------------------------------------------------ Time Machine

    private void resetPeriodCounters() {
        periodInserted = 0;
        periodUpdated = 0;
        periodStartedAt = System.currentTimeMillis();
    }

    private void finishPeriod(String period, int remoteTotal, boolean empty) {
        long local = countLocalDistinct(period);
        long duration = System.currentTimeMillis() - periodStartedAt;
        PeriodResult res = new PeriodResult(period, periodInserted, periodUpdated, remoteTotal, local, duration, empty);

        if (empty) {
            event(TM, WARN, period, "Bytel ne retourne aucun EPS pour " + period + ".");
        } else {
            event(TM, SUCCESS, period, String.format("Scan %s terminé en %s : %d nouveaux, %d mis à jour. Base: %d / Bytel: %d EPS",
                    period, formatDuration(duration), periodInserted, periodUpdated, local, remoteTotal));
            if (remoteTotal > 0 && local < remoteTotal) {
                event(TM, WARN, period, (remoteTotal - local) + " EPS manquants sur " + period
                        + " (" + local + "/" + remoteTotal + ") — repris à la prochaine passe.");
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
                            event(TM, WARN, activePeriod, "Serveur Bouygues surchargé (HTTP " + code + "), ralentissement auto. Offset " + localOffset);
                            timeMachineStatus = "Surcharge - " + throttle.describe();
                        } else {
                            event(TM, ERROR, activePeriod, "Erreur HTTP " + code);
                            timeMachineStatus = "Erreur HTTP " + code;
                            sleep(10000);
                        }
                    } catch (ResourceAccessException e) {
                        event(TM, WARN, activePeriod, "Timeout Bytel à l'offset " + localOffset + ", ralentissement auto.");
                        timeMachineStatus = "Timeout - " + throttle.describe();
                    } catch (Exception e) {
                        log.error("Erreur inattendue", e);
                        timeMachineStatus = "Glitch Bouygues - Retry";
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
        timeMachineStatus = isRunning ? "Passe terminée" : "Arrêté";
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

    /** Upsert d'un lot. Retourne {insérés, mis à jour}. */
    private int[] upsertBatch(List<Intervention> incomingData, String activePeriod) {
        int[] counts = new int[2];
        transactionTemplate.executeWithoutResult(status -> {
            List<String> incomingEpsIds = incomingData.stream().map(Intervention::getIdIntervention)
                    .filter(id -> id != null && !id.isEmpty()).toList();
            Map<String, Intervention> existingMap = interventionRepository.findByIdInterventionIn(incomingEpsIds).stream()
                    .collect(Collectors.toMap(Intervention::getIdIntervention, i -> i, (i1, i2) -> i1));

            for (Intervention incoming : incomingData) {
                if (incoming.getIdIntervention() == null || incoming.getIdIntervention().isEmpty()) continue;
                Intervention existing = existingMap.get(incoming.getIdIntervention());
                String realPeriod = incoming.getPeriode() != null ? incoming.getPeriode().replace("-", "_") : activePeriod;

                if (existing == null) {
                    existing = incoming;
                    existing.setId(null);
                    existing.setSourceIngestion("TIME_MACHINE");
                    existing.setPeriode(realPeriod);
                    existingMap.put(existing.getIdIntervention(), existing);
                    counts[0]++;
                } else {
                    existing.setEtat(incoming.getEtat());
                    existing.setDateModificationEtat(incoming.getDateModificationEtat());
                    existing.setTypeIntervention(incoming.getTypeIntervention());
                    existing.setMainteneur(incoming.getMainteneur());
                    existing.setPayloadRecu(incoming.getPayloadRecu());
                    existing.setPeriode(realPeriod);
                    counts[1]++;
                }
            }
            interventionRepository.saveAll(existingMap.values());
        });
        return counts;
    }

    // ------------------------------------------------------------ Healer

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private void backgroundHealerLoop() {
        healerStatus = "En veille";
        boolean announcedUpToDate = false;

        while (isHealing) {
            try {
                long missingCount = interventionRepository.countInterventionsWithMissingDetails();
                if (missingCount == 0) {
                    healTotal = 0;
                    healCurrent = 0;
                    healerStatus = "Base 100% à jour";
                    if (!announcedUpToDate) {
                        event(HEALER, SUCCESS, null, "File vide : tous les EPS ont leurs détails.");
                        announcedUpToDate = true;
                    }
                    sleep(10000);
                    continue;
                }
                announcedUpToDate = false;

                healTotal = (int) missingCount;
                healCurrent = 0;

                List<Intervention> chunk = "TIME_MACHINE".equals(healerMode)
                        ? interventionRepository.findMissingDetailsAsc(healerChunkSize)
                        : interventionRepository.findMissingDetailsDesc(healerChunkSize);

                if (chunk.isEmpty()) { sleep(5000); continue; }

                healerStatus = "Récupération détails (" + chunk.size() + " EPS) | " + throttle.describe();
                long t0 = System.currentTimeMillis();

                List<Future<int[]>> futures = new ArrayList<>();
                for (List<Intervention> batch : partition(chunk, Math.max(1, healerBatchSize))) {
                    futures.add(healerThreadPool.submit(() -> healBatch(batch)));
                }
                int ok = 0, notFound = 0, failed = 0;
                for (Future<int[]> f : futures) {
                    try {
                        int[] r = f.get();
                        ok += r[0]; notFound += r[1]; failed += r[2];
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
                recordLot(chunk.size(), ok, notFound, failed, dur, missingCount);

                healerStatus = "Lot " + done + "/" + chunk.size() + " traité | " + throttle.describe();
                if (done > 0) {
                    event(HEALER, failed > 0 ? WARN : SUCCESS, null, String.format(
                            "Lot de %d EPS en %s : %d enrichis, %d sans détail chez Bytel, %d échecs réseau. Reste %d.",
                            done, formatDuration(dur), ok, notFound, failed, Math.max(0, missingCount - done)));
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

    /** Soigne un petit lot d'EPS. Retourne {enrichis, absents chez Bytel, échecs}. */
    @SuppressWarnings("unchecked")
    private int[] healBatch(List<Intervention> batch) {
        if (!isHealing) return new int[3];
        List<String> idsToHeal = batch.stream().map(Intervention::getIdIntervention).toList();
        Map<String, Object> healedData = null;

        for (int attempt = 1; attempt <= 3 && isHealing; attempt++) {
            if (!throttle.acquire(() -> isHealing)) return new int[3];
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
            } catch (ResourceAccessException e) {
                handleTimeout();
                lastHealError = "Timeout Bytel";
            } catch (Exception e) {
                throttle.onError();
                lastHealError = e.getMessage();
            }
        }

        if (healedData == null && !isHealing) return new int[3]; // arrêt : on ne marque rien en fantôme

        // 2e chance : sous charge, le proxy PHP peut répondre ok=true en omettant des IDs.
        if (healedData != null && isHealing) {
            final Map<String, Object> first = healedData;
            List<String> missing = idsToHeal.stream().filter(id -> !hasDetail(first.get(id))).toList();
            if (!missing.isEmpty() && throttle.acquire(() -> isHealing)) {
                long t0 = System.currentTimeMillis();
                try {
                    Map<String, Object> retry = phpApiClient.healData(missing);
                    throttle.onSuccess(System.currentTimeMillis() - t0);
                    if (retry != null && Boolean.TRUE.equals(retry.get("ok")) && retry.get("data") instanceof Map<?, ?> rd) {
                        int rec = 0;
                        Map<String, Object> merged = new HashMap<>(first);
                        for (String id : missing) {
                            Object d = rd.get(id);
                            if (hasDetail(d)) { merged.put(id, d); rec++; }
                        }
                        healedData = merged;
                        if (rec > 0) hRecovered.addAndGet(rec);
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

        int ok = 0, notFound = 0, failed = 0;
        List<Object[]> updates = new ArrayList<>(batch.size());
        for (Intervention inv : batch) {
            String detail = "{}";
            if (healedData == null) {
                failed++;
            } else {
                Object detailObj = healedData.get(inv.getIdIntervention());
                if (!hasDetail(detailObj)) {
                    notFound++;
                } else if (detailObj instanceof String str) {
                    detail = str;
                    ok++;
                } else {
                    try { detail = objectMapper.writeValueAsString(detailObj); ok++; }
                    catch (Exception ex) { notFound++; }
                }
            }
            updates.add(new Object[]{detail, inv.getId()});
        }
        jdbcTemplate.batchUpdate("UPDATE interventions SET detail_intervention = ? WHERE id = ?", updates);

        hEnriched.addAndGet(ok);
        hNotFound.addAndGet(notFound);
        hFailed.addAndGet(failed);
        if (failed > 0) hFailedBatches.incrementAndGet();
        if (ok > 0) lastHealSuccessAt = System.currentTimeMillis();
        return new int[]{ok, notFound, failed};
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

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", isHealing);
        m.put("mode", healerMode);
        m.put("status", healerStatus);
        m.put("queue", interventionRepository.countInterventionsWithMissingDetails());
        m.put("phantoms", interventionRepository.countPhantoms());
        m.put("enriched", hEnriched.get());
        m.put("not_found", hNotFound.get());
        m.put("failed", hFailed.get());
        m.put("failed_batches", hFailedBatches.get());
        m.put("recovered", hRecovered.get());
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

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).filter(Objects::nonNull).orElse(0);
    }
}
