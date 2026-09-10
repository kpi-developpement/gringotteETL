package com.kyntus.gringotts_sync.service;

import com.kyntus.gringotts_sync.domain.ActionLog;
import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.dto.ExportResponse;
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
    private volatile String healerMode = "RADAR";

    private volatile int healTotal = 0;
    private volatile int healCurrent = 0;
    private volatile long syncStartTime = 0;
    private volatile int totalProcessedSinceStart = 0;
    private volatile String currentEta = "En attente...";

    private volatile String radarStatus = "En veille";
    private volatile String healerStatus = "En veille";
    private final List<String> recentAlerts = new CopyOnWriteArrayList<>();

    private volatile long totalRadarProcessed = 0;
    private volatile long totalHealerProcessed = 0;
    private volatile long totalPeriodProcessed = 0;

    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";

    private static final int IONOS_EXPORT_BATCH = 300;
    private static final int RADAR_BATCH = 100;
    private static final int TIME_MACHINE_BATCH = 300;

    private volatile Queue<String> periodQueue = new ConcurrentLinkedQueue<>();
    private volatile String currentPeriod = null;

    private Thread radarThread;
    private Thread healerThread;

    public boolean isRunning() { return isRunning; }
    public boolean isHealing() { return isHealing; }
    public String getHealerMode() { return healerMode; }
    public int getHealTotal() { return healTotal; }
    public int getHealCurrent() { return healCurrent; }
    public String getCurrentEta() { return currentEta; }
    public String getRadarStatus() { return radarStatus; }
    public String getHealerStatus() { return healerStatus; }
    public List<String> getRecentAlerts() { return recentAlerts; }
    public long getTotalRadarProcessed() { return totalRadarProcessed; }
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
        if (isRunning || (radarThread != null && radarThread.isAlive())) {
            log.warn("🚨 Tentative de démarrage bloquée : Un processus Radar est déjà en cours !");
            return;
        }

        periodQueue.clear();
        periodQueue.addAll(periods);
        currentPeriod = periodQueue.poll();
        totalPeriodProcessed = 0;

        // 🛡️ L'FIX HNA : On vérifie l'offset spécifique de CE mois
        int savedOffset = getSavedState("offset_" + currentPeriod);

        if (savedOffset > 0) {
            log.info("Reprise de la période : {} à l'offset {}", currentPeriod, savedOffset);
            addAlert("[TIME MACHINE] Reprise de la période " + currentPeriod + " à l'offset " + savedOffset);
        } else {
            log.info("Démarrage TIME MACHINE avec {} périodes. Première: {}", periods.size(), currentPeriod);
            addAlert("[TIME MACHINE] Démarrage de la période " + currentPeriod);
        }

        startSyncInternal();
    }

    public synchronized void startSync() {
        if (isRunning || (radarThread != null && radarThread.isAlive())) {
            log.warn("🚨 Tentative de démarrage bloquée : Un processus Radar est déjà en cours !");
            return;
        }
        currentPeriod = null;
        periodQueue.clear();
        log.info("Démarrage STANDARD (Offset Global).");
        addAlert("[SYSTEM] Démarrage Standard (Radar Global)");
        startSyncInternal();
    }

    private void startSyncInternal() {
        isRunning = true;
        currentEta = "Initialisation...";
        radarStatus = "Démarrage en cours";
        syncStartTime = System.currentTimeMillis();
        totalProcessedSinceStart = 0;

        radarThread = new Thread(this::circularRadarLoop);
        radarThread.start();
    }

    public synchronized void stopSync() {
        isRunning = false;
        currentEta = "Arrêté";
        radarStatus = "Arrêt demandé";
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

        // 🛡️ L'FIX HNA : On supprime TOUS les offsets de la base de données
        syncStateRepository.deleteAll();

        totalRadarProcessed = 0;
        totalHealerProcessed = 0;
        totalPeriodProcessed = 0;
        currentPeriod = null;
        periodQueue.clear();

        log.warn("PURGE TOTALE effectuée.");
        addAlert("[MAINTENANCE] Base de données purgée avec succès.");
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

    private void circularRadarLoop() {
        radarStatus = "En cours d'aspiration";
        log.info("Thread Radar Circulaire Démarré.");

        // 🛡️ L'FIX HNA : On charge l'offset spécifique au mois en cours
        int localOffset = currentPeriod != null ? getSavedState("offset_" + currentPeriod) : getSavedState(OFFSET_KEY);
        int localTotalApi = currentPeriod != null ? getSavedState("total_" + currentPeriod) : getSavedState(TOTAL_KEY);

        while (isRunning) {
            try {
                final String activePeriod = currentPeriod;

                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    try {
                        ExportResponse exportResp = phpApiClient.export(IONOS_EXPORT_BATCH);
                        if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                            List<Intervention> incomingData = exportResp.getData();
                            List<Long> idsToAck = incomingData.stream().map(Intervention::getId).filter(id -> id != null && id > 0).collect(Collectors.toList());

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
                                        existing.setSourceIngestion(activePeriod != null ? "TIME_MACHINE" : "RADAR");

                                        if (existing.getActionsLog() != null) {
                                            for (ActionLog l : existing.getActionsLog()) l.setId(null);
                                        }
                                        existingMap.put(existing.getIdIntervention(), existing);
                                    } else {
                                        existing.setEtat(incoming.getEtat());
                                        existing.setDateModificationEtat(incoming.getDateModificationEtat());
                                        existing.setTypeIntervention(incoming.getTypeIntervention());
                                        existing.setMainteneur(incoming.getMainteneur());
                                        if (incoming.getDetailIntervention() != null && !incoming.getDetailIntervention().isEmpty() && !incoming.getDetailIntervention().equals("null")) {
                                            existing.setDetailIntervention(incoming.getDetailIntervention());
                                        }
                                        existing.setPayloadRecu(incoming.getPayloadRecu());
                                        if (existing.getActionsLog() != null) existing.getActionsLog().clear();
                                        else existing.setActionsLog(new ArrayList<>());
                                        if (incoming.getActionsLog() != null) {
                                            for (ActionLog newLog : incoming.getActionsLog()) {
                                                newLog.setId(null);
                                                existing.getActionsLog().add(newLog);
                                            }
                                        }
                                    }
                                }
                                interventionRepository.saveAll(existingMap.values());
                            });

                            if (!idsToAck.isEmpty()) {
                                try {
                                    phpApiClient.acknowledge(idsToAck);
                                } catch (Exception e) {
                                    log.warn("⚠️ Le serveur PHP a rejeté l'ACK. On continue.");
                                }
                            } else {
                                bufferHasData = false;
                            }
                        } else {
                            bufferHasData = false;
                        }
                    } catch (Exception e) {
                        radarStatus = "Erreur Vidage IONOS";
                        bufferHasData = false;
                        sleep(5000);
                    }
                }

                if (!isRunning) break;

                int dbOffset = currentPeriod != null ? getSavedState("offset_" + currentPeriod) : getSavedState(OFFSET_KEY);
                if (Math.abs(dbOffset - localOffset) > 1000) {
                    localOffset = dbOffset;
                    log.warn("🔄 Offset forcé détecté. Mise à jour de la RAM vers : {}", localOffset);
                }

                if (localTotalApi > 0 && localOffset >= localTotalApi) {
                    if (currentPeriod != null) {
                        log.info("Période {} terminée à 100%.", currentPeriod);
                        addAlert("✅ [TIME MACHINE] Période " + currentPeriod + " terminée.");

                        currentPeriod = periodQueue.poll();

                        if (currentPeriod == null) {
                            log.info("Toutes les périodes ont été traitées. Arrêt.");
                            addAlert("🎉 [TIME MACHINE] Toutes les périodes ont été traitées !");
                            stopSync();
                            break;
                        } else {
                            log.info("Passage à la période suivante: {}", currentPeriod);
                            addAlert("📅 [TIME MACHINE] Passage à : " + currentPeriod);

                            // 🛡️ L'FIX HNA : On charge l'offset du NOUVEAU mois
                            localOffset = getSavedState("offset_" + currentPeriod);
                            localTotalApi = getSavedState("total_" + currentPeriod);
                            totalProcessedSinceStart = 0;
                            syncStartTime = System.currentTimeMillis();
                            sleep(2000);
                        }
                    } else {
                        log.info("Cycle Standard terminé. Retour à l'offset 0.");
                        saveState(OFFSET_KEY, 0);
                        localOffset = 0;
                        currentEta = "Nouveau Cycle";
                        radarStatus = "Cycle 100% terminé. Pause 30s.";
                        sleep(30000);
                    }
                } else {
                    radarStatus = currentPeriod != null
                            ? "Scan [" + currentPeriod + "] en cours..."
                            : "Scan Bouygues en cours...";
                }

                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        int currentBatchSize = (currentPeriod != null) ? TIME_MACHINE_BATCH : RADAR_BATCH;

                        log.debug("Envoi commande Import -> Offset: {}, Limite: {}, Période: {}", localOffset, currentBatchSize, currentPeriod);
                        ImportResponse importResp = phpApiClient.triggerImport(localOffset, currentBatchSize, currentPeriod);

                        if (importResp != null && importResp.isOk()) {

                            if (importResp.getBatchCount() == 0) {
                                log.warn("Bouygues a retourné 0 résultat. Avancement forcé de la zone.");
                                localTotalApi = importResp.getTotalApi() > 0 ? importResp.getTotalApi() : 1;
                                localOffset = localTotalApi;

                                if (currentPeriod != null) {
                                    saveState("offset_" + currentPeriod, localOffset);
                                    saveState("total_" + currentPeriod, localTotalApi);
                                } else {
                                    saveState(OFFSET_KEY, localOffset);
                                    saveState(TOTAL_KEY, localTotalApi);
                                }
                                importSuccess = true;
                                break;
                            }

                            localOffset = importResp.getNextOffset();
                            localTotalApi = importResp.getTotalApi();

                            if (currentPeriod != null) {
                                saveState("offset_" + currentPeriod, localOffset);
                                saveState("total_" + currentPeriod, localTotalApi);
                                totalPeriodProcessed += importResp.getBatchCount();
                            } else {
                                saveState(OFFSET_KEY, localOffset);
                                saveState(TOTAL_KEY, localTotalApi);
                                totalRadarProcessed += importResp.getBatchCount();
                            }

                            totalProcessedSinceStart += importResp.getBatchCount();

                            if (totalProcessedSinceStart > 0 && syncStartTime > 0) {
                                long elapsedMillis = System.currentTimeMillis() - syncStartTime;
                                long millisPerItem = elapsedMillis / totalProcessedSinceStart;
                                int remainingItems = localTotalApi - localOffset;
                                currentEta = formatDuration(remainingItems * millisPerItem);
                            }
                            importSuccess = true;
                            radarStatus = "Vitesse: " + currentBatchSize + " EPS (Offset: " + localOffset + ")";
                            sleep(1000);
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        String body = e.getResponseBodyAsString();
                        log.error("Erreur API PHP (HTTP {}): {}", e.getStatusCode(), body);

                        if (e.getStatusCode().value() == 404) {
                            addAlert("[RADAR] Erreur 404 (URL introuvable) - Vérifiez AppConfig");
                            radarStatus = "Erreur HTTP 404 (URL introuvable)";
                            sleep(15000);
                        }
                        else if (e.getStatusCode().value() == 403 || body.contains("Access Denied")) {
                            addAlert("[RADAR] Bloqué par le Pare-feu Bouygues (Akamai). Veille 15m.");
                            radarStatus = "Banni (Pause 15 min)";
                            sleep(15 * 60 * 1000);
                        }
                        else if (e.getStatusCode().value() == 500 || e.getStatusCode().value() == 504 || body.contains("Timeout")) {
                            addAlert("[RADAR] Serveur Bouygues Surchargé (HTTP " + e.getStatusCode() + ")");
                            radarStatus = "Erreur HTTP " + e.getStatusCode() + " - Retry...";
                            sleep(15000);
                        }
                        else {
                            addAlert("[RADAR] Erreur HTTP " + e.getStatusCode());
                            radarStatus = "Erreur HTTP " + e.getStatusCode();
                            sleep(10000);
                        }
                    } catch (Exception e) {
                        log.error("Erreur de connexion inattendue", e);
                        radarStatus = "Erreur Connexion";
                        sleep(10000);
                    }
                }

                if (!importSuccess && isRunning) {
                    radarStatus = "Échecs répétés, pause 30s";
                    sleep(30000);
                }

            } catch (Exception e) {
                log.error("Exception critique dans la boucle Radar", e);
                radarStatus = "Erreur Critique";
                sleep(5000);
            }
        }
        log.info("Thread Radar Circulaire Arrêté.");
        radarStatus = "Arrêté";
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

                List<String> idsToHeal = chunk.stream().map(Intervention::getIdIntervention).toList();
                boolean success = false;

                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        healerStatus = "Récupération détails (" + idsToHeal.size() + " EPS)";
                        Map<String, Object> response = phpApiClient.healData(idsToHeal);

                        if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                            Object rawData = response.get("data");
                            Map<String, String> healedData = new HashMap<>();
                            if (rawData instanceof Map) healedData = (Map<String, String>) rawData;

                            for (Intervention inv : chunk) {
                                String detailStr = healedData.get(inv.getIdIntervention());
                                if (detailStr != null) inv.setDetailIntervention(detailStr);
                                else inv.setDetailIntervention("{}");
                            }

                            interventionRepository.saveAll(chunk);
                            healCurrent += chunk.size();
                            totalHealerProcessed += chunk.size();
                            success = true;
                            healerStatus = "Lot sauvegardé avec succès";
                            sleep(1000);
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        String body = e.getResponseBodyAsString();
                        log.error("Erreur Healer HTTP {} : {}", e.getStatusCode(), body);

                        if (e.getStatusCode().value() == 403 || body.contains("Access Denied")) {
                            addAlert("[HEALER] Pare-feu Bouygues déclenché. Veille 15m.");
                            healerStatus = "Banni (Pause 15 min)";
                            sleep(15 * 60 * 1000);
                        } else {
                            healerStatus = "Erreur HTTP " + e.getStatusCode();
                            sleep(5000);
                        }
                    } catch (Exception e) {
                        log.error("Erreur connexion Healer", e);
                        healerStatus = "Erreur Connexion";
                        sleep(5000);
                    }
                }

                if (!success && isHealing) {
                    for (Intervention inv : chunk) inv.setDetailIntervention("{}");
                    interventionRepository.saveAll(chunk);
                }

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