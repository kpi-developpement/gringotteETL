package com.kyntus.gringotts_sync.service;

import com.kyntus.gringotts_sync.domain.ActionLog;
import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import com.kyntus.gringotts_sync.integration.PhpApiClient;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrator {

    private final PhpApiClient phpApiClient;
    private final InterventionRepository interventionRepository;
    private final SyncStateRepository syncStateRepository;
    private final TransactionTemplate transactionTemplate;

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

    private final ForkJoinPool healerThreadPool = new ForkJoinPool(3);

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

    private void addAlert(String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        recentAlerts.add(0, "[" + time + "] " + message);
        if (recentAlerts.size() > 20) {
            recentAlerts.remove(recentAlerts.size() - 1);
        }
        log.warn("INTERFACE_ALERT: {}", message);
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

                        // 🚀 L'FIX HNA : Le Proxy PHP nous donne la data directement
                        ImportResponse importResp = phpApiClient.triggerImport(localOffset, TIME_MACHINE_BATCH, activePeriod);

                        if (importResp != null && importResp.isOk()) {

                            if (importResp.getBatchCount() == 0) {
                                log.warn("Bouygues a retourné 0 résultat. Avancement forcé de la zone.");
                                localTotalApi = importResp.getTotalApi() > 0 ? importResp.getTotalApi() : 1;
                                localOffset = localTotalApi;
                                saveState("offset_" + activePeriod, localOffset);
                                saveState("total_" + activePeriod, localTotalApi);
                                importSuccess = true;
                                break;
                            }

                            // 🚀 L'FIX HNA : On sauvegarde la donnée reçue directement dans PostgreSQL
                            List<Intervention> incomingData = importResp.getData();
                            if (incomingData != null && !incomingData.isEmpty()) {
                                transactionTemplate.executeWithoutResult(status -> {
                                    List<String> incomingEpsIds = incomingData.stream().map(Intervention::getIdIntervention).filter(id -> id != null && !id.isEmpty()).toList();
                                    List<Intervention> existingData = interventionRepository.findByIdInterventionIn(incomingEpsIds);
                                    Map<String, Intervention> existingMap = existingData.stream().collect(Collectors.toMap(Intervention::getIdIntervention, i -> i, (i1, i2) -> i1));

                                    for (Intervention incoming : incomingData) {
                                        if (incoming.getIdIntervention() == null || incoming.getIdIntervention().isEmpty()) continue;
                                        Intervention existing = existingMap.get(incoming.getIdIntervention());

                                        if (existing == null) {
                                            existing = incoming;
                                            existing.setId(null);
                                            existing.setSourceIngestion("TIME_MACHINE");
                                            existing.setPeriode(activePeriod);
                                            existingMap.put(existing.getIdIntervention(), existing);
                                        } else {
                                            existing.setEtat(incoming.getEtat());
                                            existing.setDateModificationEtat(incoming.getDateModificationEtat());
                                            existing.setTypeIntervention(incoming.getTypeIntervention());
                                            existing.setMainteneur(incoming.getMainteneur());
                                            existing.setPayloadRecu(incoming.getPayloadRecu());
                                            existing.setPeriode(activePeriod);
                                        }
                                    }
                                    interventionRepository.saveAll(existingMap.values());
                                });
                            }

                            int newOffset = importResp.getNextOffset();
                            int newTotal = importResp.getTotalApi();

                            if (localTotalApi == 0 || (newTotal > 0 && Math.abs(newTotal - localTotalApi) < 10000)) {
                                localTotalApi = newTotal;
                            }

                            if (newOffset > 0 && newOffset <= localOffset) {
                                log.error("🚨 GLITCH BOUYGUES DETECTE : L'API a tenté de ramener l'offset de {} à {}. On force la continuité !", localOffset, newOffset);
                                addAlert("⚠️ Glitch Bouygues ignoré (Offset protégé à " + localOffset + ")");
                                localOffset += TIME_MACHINE_BATCH;
                            } else {
                                localOffset = newOffset;
                            }

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
                            timeMachineStatus = "Vitesse: " + TIME_MACHINE_BATCH + " EPS (Offset: " + localOffset + ")";

                            // Zéro Sleep pour la vitesse maximale !
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
                            addAlert("[TIME MACHINE] Bloqué par le Pare-feu Bouygues (Akamai). Veille 15m.");
                            timeMachineStatus = "Banni (Pause 15 min)";
                            sleep(15 * 60 * 1000);
                        }
                        else if (e.getStatusCode().value() == 500 || e.getStatusCode().value() == 504 || body.contains("Timeout")) {
                            addAlert("[TIME MACHINE] Serveur Bouygues Surchargé (HTTP " + e.getStatusCode() + ")");
                            timeMachineStatus = "Surcharge - Retry...";
                            sleep(15000);
                        }
                        else {
                            addAlert("[TIME MACHINE] Erreur HTTP " + e.getStatusCode());
                            timeMachineStatus = "Erreur HTTP " + e.getStatusCode();
                            sleep(10000);
                        }
                    } catch (Exception e) {
                        log.error("Erreur de connexion inattendue", e);
                        timeMachineStatus = "Erreur Connexion";
                        sleep(10000);
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

                List<Intervention> chunk;
                if ("TIME_MACHINE".equals(healerMode)) {
                    chunk = interventionRepository.findInterventionsWithMissingDetailsAsc();
                } else {
                    chunk = interventionRepository.findInterventionsWithMissingDetailsDesc();
                }

                if (chunk.isEmpty()) { sleep(5000); continue; }

                healerStatus = "Récupération détails (" + chunk.size() + " EPS en parallèle)";

                List<List<Intervention>> batches = partition(chunk, 20);

                healerThreadPool.submit(() -> {
                    batches.parallelStream().forEach(batch -> {
                        List<String> idsToHeal = batch.stream().map(Intervention::getIdIntervention).toList();
                        boolean success = false;

                        for (int attempt = 1; attempt <= 3; attempt++) {
                            try {
                                Map<String, Object> response = phpApiClient.healData(idsToHeal);

                                if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                                    Object rawData = response.get("data");
                                    Map<String, String> healedData = new HashMap<>();
                                    if (rawData instanceof Map) healedData = (Map<String, String>) rawData;

                                    for (Intervention inv : batch) {
                                        String detailStr = healedData.get(inv.getIdIntervention());
                                        if (detailStr != null) inv.setDetailIntervention(detailStr);
                                        else inv.setDetailIntervention("{}");
                                    }
                                    success = true;
                                    break;
                                }
                            } catch (RestClientResponseException e) {
                                if (e.getStatusCode().value() == 403 || e.getResponseBodyAsString().contains("Access Denied")) {
                                    addAlert("[HEALER] Pare-feu Bouygues déclenché. Veille 15m.");
                                    sleep(15 * 60 * 1000);
                                } else if (e.getStatusCode().value() == 500 || e.getStatusCode().value() == 504) {
                                    sleep(5000);
                                } else {
                                    sleep(2000);
                                }
                            } catch (Exception e) {
                                sleep(2000);
                            }
                        }

                        if (!success && isHealing) {
                            for (Intervention inv : batch) inv.setDetailIntervention("{}");
                        }
                    });
                }).get();

                interventionRepository.saveAll(chunk);
                healCurrent += chunk.size();
                totalHealerProcessed += chunk.size();
                healerStatus = "Lot de " + chunk.size() + " sauvegardé (Vitesse Max)";

                sleep(300);

            } catch (Exception e) {
                log.error("Exception critique Healer", e);
                healerStatus = "Erreur Critique";
                sleep(10000);
            }
        }
        log.info("Thread Background Healer Arrêté.");
        healerStatus = "Arrêté";
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