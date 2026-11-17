package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyntus.gringotts_sync.domain.ActionLog;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrator {

    private final PhpApiClient phpApiClient;
    private final InterventionRepository interventionRepository;
    private final SyncStateRepository syncStateRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final BytelThrottle throttle;
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

    private volatile Queue<String> periodQueue = new ConcurrentLinkedQueue<>();
    private volatile String currentPeriod = null;

    private Thread timeMachineThread;
    private Thread healerThread;

    private final ExecutorService healerThreadPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "healer-worker");
        t.setDaemon(true);
        return t;
    });

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
    public List<String> getQueuedPeriods() { return new ArrayList<>(periodQueue); }

    /** Répare les flags "zombies" (flag à true mais thread mort). Retourne true si qqch a été corrigé. */
    public synchronized boolean watchdog() {
        boolean fixed = false;
        if (isRunning && (timeMachineThread == null || !timeMachineThread.isAlive())) {
            isRunning = false;
            timeMachineStatus = "Récupéré par le watchdog";
            addAlert("[WATCHDOG] Thread Time Machine mort détecté, flag réinitialisé.");
            fixed = true;
        }
        if (isHealing && (healerThread == null || !healerThread.isAlive())) {
            isHealing = false;
            healerStatus = "Récupéré par le watchdog";
            addAlert("[WATCHDOG] Thread Healer mort détecté, flag réinitialisé.");
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
        addAlert("[AUTOPILOT] Sync auto: " + String.join(", ", periods));
        startPeriodSync(periods);
        return true;
    }

    /** Sonde légère : récupère le total réel Bytel d'une période (limit=1). -1 si indisponible. */
    public int probeRemoteTotal(String period) {
        if (!throttle.acquire(() -> true)) return -1;
        long t0 = System.currentTimeMillis();
        try {
            ImportResponse resp = phpApiClient.triggerImport(0, 1, period);
            throttle.onSuccess(System.currentTimeMillis() - t0);
            if (resp != null && resp.isOk() && resp.getTotalApi() > 0) {
                saveState("total_" + period, resp.getTotalApi());
                return resp.getTotalApi();
            }
            return -1;
        } catch (RestClientResponseException e) {
            handleHttpError(e);
            return -1;
        } catch (ResourceAccessException e) {
            throttle.onOverload("timeout/IO");
            return -1;
        } catch (Exception e) {
            throttle.onError();
            return -1;
        }
    }

    public long countLocalDistinct(String period) {
        return interventionRepository.countDistinctByPeriode(period);
    }

    public int getSavedOffset(String period) { return getSavedState("offset_" + period); }
    public int getSavedTotal(String period) { return getSavedState("total_" + period); }

    private void handleHttpError(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        if (code == 403 || body.contains("Access Denied")) {
            throttle.onBlocked();
        } else if (code == 429 || code >= 500 || body.contains("Timeout")) {
            throttle.onOverload("HTTP " + code);
        } else {
            throttle.onError();
        }
    }

    public void addAlert(String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        recentAlerts.add(0, "[" + time + "] " + message);
        if (recentAlerts.size() > 20) {
            recentAlerts.remove(recentAlerts.size() - 1);
        }
        log.warn("INTERFACE_ALERT: {}", message);
    }

    // 🚀 L'FIX HNA: Fonction jdida kaddir scan b dbt 3la l'mois li 3zelna mn React
    public synchronized int fixPeriodsForMonth(String targetPeriod) {
        log.info("Lancement du nettoyage des intrus pour la période: {}", targetPeriod);
        addAlert("[MAINTENANCE] Scan de " + targetPeriod + " pour trouver les EPS intrus...");

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
                            // Yla l9a l'période l'7a9i9ya mkhtalfa 3la l'mois li rahoum fih, kayrigelha
                            if (!realPeriod.equals(targetPeriod)) {
                                inv.setPeriode(realPeriod);
                                toUpdate.add(inv);
                                fixedCount++;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore les JSON lmkhesrin
                }
            }
        }

        if (!toUpdate.isEmpty()) {
            interventionRepository.saveAll(toUpdate);
        }

        addAlert("[MAINTENANCE] " + fixedCount + " EPS intrus trouvés et déplacés depuis " + targetPeriod);
        return fixedCount;
    }

    public synchronized void startPeriodSync(List<String> periods) {
        if (isRunning || (timeMachineThread != null && timeMachineThread.isAlive())) {
            log.warn("🚨 Tentative de démarrage bloquée : Un processus est déjà en cours !");
            return;
        }

        periodQueue.clear();
        periodQueue.addAll(periods);
        currentPeriod = periodQueue.poll();
        totalPeriodProcessed = 0;

        int savedOffset = getSavedState("offset_" + currentPeriod);

        if (savedOffset > 0) {
            log.info("Reprise de la période : {} à l'offset {}", currentPeriod, savedOffset);
            addAlert("[TIME MACHINE] Reprise de la période " + currentPeriod + " à l'offset " + savedOffset);
        } else {
            log.info("Démarrage TIME MACHINE avec {} périodes. Première: {}", periods.size(), currentPeriod);
            addAlert("[TIME MACHINE] Démarrage de la période " + currentPeriod);
        }

        isRunning = true;
        currentEta = "Initialisation...";
        timeMachineStatus = "Démarrage en cours";
        syncStartTime = System.currentTimeMillis();
        totalProcessedSinceStart = 0;

        timeMachineThread = new Thread(this::timeMachineLoop);
        timeMachineThread.start();
    }

    public synchronized void rescanPeriod(String period) {
        if (isRunning) {
            addAlert("⚠️ Veuillez stopper la synchronisation en cours avant de lancer un Rescan.");
            return;
        }
        log.info("Lancement du Smart Rescan pour la période: {}", period);
        addAlert("[SMART RESCAN] Repassage sur " + period + " pour récupérer les EPS manquants...");

        saveState("offset_" + period, 0);
        startPeriodSync(Collections.singletonList(period));
    }

    public synchronized void stopSync() {
        isRunning = false;
        currentEta = "Arrêté";
        timeMachineStatus = "Arrêt demandé";
        log.info("Arrêt du Daemon demandé.");
        addAlert("[SYSTEM] Arrêt du système demandé");
    }

    public synchronized void startHealer(String mode) {
        if (isHealing || (healerThread != null && healerThread.isAlive())) {
            log.warn("🚨 Healer déjà en cours d'exécution !");
            return;
        }
        isHealing = true;
        healerMode = mode;
        healerStatus = "Démarrage en cours";
        log.info("Démarrage du Healer en mode : {}", mode);
        addAlert("[HEALER] Démarrage en mode " + mode);

        healerThread = new Thread(this::backgroundHealerLoop);
        healerThread.start();
    }

    public synchronized void stopHealer() {
        isHealing = false;
        healerStatus = "Arrêt demandé";
        log.info("Arrêt du Healer demandé.");
        addAlert("[HEALER] Arrêt demandé");
    }

    public synchronized void purgeDatabase() {
        stopSync();
        stopHealer();
        sleep(2000);

        try { phpApiClient.resetIonos(); } catch (Exception e) { log.error("Erreur reset IONOS", e); }

        interventionRepository.truncateInterventions();
        syncStateRepository.deleteAll();

        totalHealerProcessed = 0;
        totalPeriodProcessed = 0;
        currentPeriod = null;
        periodQueue.clear();

        log.warn("PURGE TOTALE effectuée.");
        addAlert("[MAINTENANCE] Base de données purgée avec succès.");
    }

    public synchronized int purgePeriod(String period) {
        String cleanPeriod = period.replace("_", "-");
        String dbPeriod = period;

        interventionRepository.deleteLogsByPeriodNative(cleanPeriod, dbPeriod);
        int deletedCount = interventionRepository.deleteInterventionsByPeriodNative(cleanPeriod, dbPeriod);

        syncStateRepository.deleteById("offset_" + period);
        syncStateRepository.deleteById("total_" + period);

        addAlert("[MAINTENANCE] Période " + period + " purgée (" + deletedCount + " supprimées).");
        return deletedCount;
    }

    public void cleanDuplicatesTask() {
        log.info("Smart Clean (Dédoublonnage) démarré.");
        addAlert("[MAINTENANCE] Smart Clean lancé");
        int totalDeleted = 0;
        while (true) {
            List<Long> duplicateIds = interventionRepository.findDuplicateIds();
            if (duplicateIds.isEmpty()) break;
            interventionRepository.deleteLogsByIds(duplicateIds);
            int deleted = interventionRepository.deleteInterventionsByIds(duplicateIds);
            totalDeleted += deleted;
        }
        log.info("Smart Clean terminé : {} doublons supprimés.", totalDeleted);
        addAlert("[MAINTENANCE] Smart Clean terminé. " + totalDeleted + " doublons.");
    }

    public int retryFailedHeals() {
        int count = interventionRepository.resetFailedHeals();
        addAlert("[HEALER] " + count + " EPS Fantômes remis en file d'attente.");
        return count;
    }

    private void timeMachineLoop() {
        timeMachineStatus = "En cours d'aspiration";
        log.info("Thread Time Machine Démarré.");

        while (isRunning && currentPeriod != null) {
            try {
                final String activePeriod = currentPeriod;
                int localOffset = getSavedState("offset_" + activePeriod);
                int localTotalApi = getSavedState("total_" + activePeriod);

                if (localTotalApi > 0 && localOffset >= localTotalApi) {
                    log.info("Période {} terminée à 100%.", activePeriod);
                    addAlert("✅ [TIME MACHINE] Période " + activePeriod + " terminée.");

                    currentPeriod = periodQueue.poll();

                    if (currentPeriod == null) {
                        log.info("Toutes les périodes ont été traitées. Arrêt.");
                        addAlert("🎉 [TIME MACHINE] Toutes les périodes ont été traitées !");
                        stopSync();
                        break;
                    } else {
                        log.info("Passage à la période suivante: {}", currentPeriod);
                        addAlert("📅 [TIME MACHINE] Passage à : " + currentPeriod);
                        totalProcessedSinceStart = 0;
                        syncStartTime = System.currentTimeMillis();
                        sleep(2000);
                        continue;
                    }
                }

                timeMachineStatus = "Scan [" + activePeriod + "] en cours...";

                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        log.debug("Envoi commande Import -> Offset: {}, Limite: {}, Période: {}", localOffset, TIME_MACHINE_BATCH, activePeriod);

                        if (!throttle.acquire(() -> isRunning)) break;
                        long t0 = System.currentTimeMillis();
                        ImportResponse importResp;
                        try {
                            importResp = phpApiClient.triggerImport(localOffset, TIME_MACHINE_BATCH, activePeriod);
                        } catch (RestClientResponseException ex) {
                            handleHttpError(ex);
                            throw ex;
                        } catch (ResourceAccessException ex) {
                            throttle.onOverload("timeout/IO");
                            throw ex;
                        } catch (RuntimeException ex) {
                            throttle.onError();
                            throw ex;
                        }
                        throttle.onSuccess(System.currentTimeMillis() - t0);

                        if (importResp != null && importResp.isOk()) {

                            if (importResp.getBatchCount() == 0) {
                                log.warn("Bouygues a retourné 0 résultat. Avancement manuel de la page pour ne pas rater de données.");
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
                                throw new RuntimeException("Glitch API Bouygues (mauvais offset retourné: " + newOffset + "). On force le retry de la page " + localOffset + " !");
                            }

                            List<Intervention> incomingData = importResp.getData();
                            if (incomingData != null && !incomingData.isEmpty()) {
                                String expectedPeriod = activePeriod.replace("_", "-");
                                String firstRecordPeriod = incomingData.get(0).getPeriode();

                                if (firstRecordPeriod != null && !firstRecordPeriod.equals(expectedPeriod)) {
                                    throw new RuntimeException("Glitch API Bouygues détecté : EPS de " + firstRecordPeriod + " reçu au lieu de " + expectedPeriod + ". Retry forcé de la page !");
                                }

                                transactionTemplate.executeWithoutResult(status -> {
                                    List<String> incomingEpsIds = incomingData.stream().map(Intervention::getIdIntervention).filter(id -> id != null && !id.isEmpty()).toList();
                                    List<Intervention> existingData = interventionRepository.findByIdInterventionIn(incomingEpsIds);
                                    Map<String, Intervention> existingMap = existingData.stream().collect(Collectors.toMap(Intervention::getIdIntervention, i -> i, (i1, i2) -> i1));

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
                                        } else {
                                            existing.setEtat(incoming.getEtat());
                                            existing.setDateModificationEtat(incoming.getDateModificationEtat());
                                            existing.setTypeIntervention(incoming.getTypeIntervention());
                                            existing.setMainteneur(incoming.getMainteneur());
                                            existing.setPayloadRecu(incoming.getPayloadRecu());
                                            existing.setPeriode(realPeriod);
                                        }
                                    }
                                    interventionRepository.saveAll(existingMap.values());
                                });
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
                                int remainingItems = localTotalApi - localOffset;
                                currentEta = formatDuration(remainingItems * millisPerItem);
                            }
                            importSuccess = true;
                            timeMachineStatus = "[" + activePeriod + "] Offset " + localOffset + "/" + localTotalApi + " | " + throttle.describe();
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        String body = e.getResponseBodyAsString();
                        log.error("Erreur API PHP (HTTP {}): {}", e.getStatusCode(), body);

                        if (e.getStatusCode().value() == 404) {
                            addAlert("[TIME MACHINE] Erreur 404 (URL introuvable)");
                            timeMachineStatus = "Erreur HTTP 404";
                            sleep(15000);
                        }
                        else if (e.getStatusCode().value() == 403 || body.contains("Access Denied")) {
                            // La pause globale est gérée par le BytelThrottle (Healer en pause aussi).
                            addAlert("[TIME MACHINE] Bloqué par le Pare-feu Bouygues (Akamai). Pause globale.");
                            timeMachineStatus = "Banni - " + throttle.describe();
                        }
                        else if (e.getStatusCode().value() == 429 || e.getStatusCode().value() >= 500 || body.contains("Timeout")) {
                            addAlert("[TIME MACHINE] Serveur Bouygues Surchargé (HTTP " + e.getStatusCode() + ") - ralentissement auto");
                            timeMachineStatus = "Surcharge - " + throttle.describe();
                        }
                        else {
                            addAlert("[TIME MACHINE] Erreur HTTP " + e.getStatusCode());
                            timeMachineStatus = "Erreur HTTP " + e.getStatusCode();
                            sleep(10000);
                        }
                    } catch (ResourceAccessException e) {
                        log.warn("Timeout / IO vers Bytel: {}", e.getMessage());
                        addAlert("[TIME MACHINE] Timeout Bytel - ralentissement auto");
                        timeMachineStatus = "Timeout - " + throttle.describe();
                    } catch (Exception e) {
                        log.error("Erreur de connexion inattendue", e);
                        timeMachineStatus = "Glitch Bouygues - Retry";
                        addAlert("⚠️ " + e.getMessage());
                        sleep(2000);
                    }
                }

                if (!importSuccess && isRunning) {
                    timeMachineStatus = "Échecs répétés, pause 30s";
                    sleep(30000);
                }

            } catch (Exception e) {
                log.error("Exception critique dans la boucle Time Machine", e);
                timeMachineStatus = "Erreur Critique";
                sleep(5000);
            }
        }
        log.info("Thread Time Machine Arrêté.");
        timeMachineStatus = "Arrêté";
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
        log.info("Thread Background Healer Démarré en mode : {}", healerMode);

        while (isHealing) {
            try {
                // Rapide grâce à l'index partiel idx_interv_missing_detail
                long missingCount = interventionRepository.countInterventionsWithMissingDetails();
                if (missingCount == 0) {
                    healTotal = 0;
                    healCurrent = 0;
                    healerStatus = "Base 100% à jour";
                    sleep(10000);
                    continue;
                }

                healTotal = (int) missingCount;
                healCurrent = 0;

                List<Intervention> chunk = "TIME_MACHINE".equals(healerMode)
                        ? interventionRepository.findMissingDetailsAsc(healerChunkSize)
                        : interventionRepository.findMissingDetailsDesc(healerChunkSize);

                if (chunk.isEmpty()) { sleep(5000); continue; }

                healerStatus = "Récupération détails (" + chunk.size() + " EPS) | " + throttle.describe();

                // Le parallélisme réel est piloté par le BytelThrottle (limite dynamique en vol).
                List<Future<Integer>> futures = new ArrayList<>();
                for (List<Intervention> batch : partition(chunk, Math.max(1, healerBatchSize))) {
                    futures.add(healerThreadPool.submit(() -> healBatch(batch)));
                }
                int done = 0;
                for (Future<Integer> f : futures) {
                    try { done += f.get(); } catch (ExecutionException ee) { log.error("Erreur batch Healer", ee.getCause()); }
                }

                healCurrent += done;
                totalHealerProcessed += done;
                healerStatus = "Lot " + done + "/" + chunk.size() + " traité | " + throttle.describe();

            } catch (Exception e) {
                log.error("Exception critique Healer", e);
                healerStatus = "Erreur Critique";
                sleep(10000);
            }
        }
        log.info("Thread Background Healer Arrêté.");
        healerStatus = "Arrêté";
    }

    /** Soigne un petit lot d'EPS et l'écrit tout de suite en base (UPDATE ciblé, pas de merge JPA). */
    @SuppressWarnings("unchecked")
    private int healBatch(List<Intervention> batch) {
        if (!isHealing) return 0;
        List<String> idsToHeal = batch.stream().map(Intervention::getIdIntervention).toList();
        Map<String, Object> healedData = null;

        for (int attempt = 1; attempt <= 3 && isHealing; attempt++) {
            if (!throttle.acquire(() -> isHealing)) return 0;
            long t0 = System.currentTimeMillis();
            try {
                Map<String, Object> response = phpApiClient.healData(idsToHeal);
                throttle.onSuccess(System.currentTimeMillis() - t0);
                if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                    Object rawData = response.get("data");
                    healedData = rawData instanceof Map ? (Map<String, Object>) rawData : new HashMap<>();
                    break;
                }
            } catch (RestClientResponseException e) {
                boolean wasPaused = throttle.isPaused();
                handleHttpError(e);
                if (!wasPaused && throttle.isPaused()) {
                    addAlert("[HEALER] " + throttle.describe());
                }
            } catch (ResourceAccessException e) {
                throttle.onOverload("timeout/IO");
            } catch (Exception e) {
                throttle.onError();
                log.warn("Erreur Healer: {}", e.getMessage());
            }
        }

        if (healedData == null && !isHealing) return 0; // arrêt demandé : on ne marque rien en fantôme

        List<Object[]> updates = new ArrayList<>(batch.size());
        for (Intervention inv : batch) {
            String detail = "{}";
            Object detailObj = healedData != null ? healedData.get(inv.getIdIntervention()) : null;
            if (detailObj instanceof String str) {
                detail = str;
            } else if (detailObj != null) {
                try { detail = objectMapper.writeValueAsString(detailObj); } catch (Exception ignored) { }
            }
            updates.add(new Object[]{detail, inv.getId()});
        }
        jdbcTemplate.batchUpdate("UPDATE interventions SET detail_intervention = ? WHERE id = ?", updates);
        return batch.size();
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
    }

    private String formatDuration(long millis) {
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

    private void saveStateString(String key, String value) {
        SyncState state = syncStateRepository.findById(key).orElse(new SyncState());
        state.setStateKey(key);
        state.setStateValueStr(value);
        syncStateRepository.save(state);
    }

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).orElse(0);
    }

    private String getSavedStateString(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValueStr).orElse("");
    }
}