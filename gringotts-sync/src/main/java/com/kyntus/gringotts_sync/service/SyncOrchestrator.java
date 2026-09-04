package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private volatile int healTotal = 0;
    private volatile int healCurrent = 0;

    // 🚀 VARIABLES POUR L'ETA
    private volatile long syncStartTime = 0;
    private volatile int totalProcessedSinceStart = 0;
    private volatile String currentEta = "Calcul en cours...";

    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";
    // 🚀 BATCH_SIZE à 50 est OBLIGATOIRE avec fetch_details=true pour éviter les Timeout de Bouygues
    private static final int BATCH_SIZE = 50;

    public boolean isRunning() { return isRunning; }
    public boolean isHealing() { return isHealing; }
    public int getHealTotal() { return healTotal; }
    public int getHealCurrent() { return healCurrent; }
    public String getCurrentEta() { return currentEta; }

    public void startSync() {
        if (isRunning || isHealing) return;
        isRunning = true;
        syncStartTime = System.currentTimeMillis();
        totalProcessedSinceStart = 0;
        currentEta = "Calcul en cours...";
        log.info("🚀 DÉMARRAGE DU MODE TURBO (LOGIQUE JAVA - FULL DETAILS)");
        new Thread(this::processLoop).start();
    }

    public void stopSync() {
        log.info("🛑 ARRÊT DEMANDÉ PAR L'UTILISATEUR");
        isRunning = false;
        currentEta = "Arrêté";
    }

    public void resetAndStartFromZero() {
        log.info("⚠️ RESET TOTAL DEMANDÉ...");
        stopSync();
        try { phpApiClient.resetIonos(); } catch (Exception e) {}
        interventionRepository.deleteAll();
        saveState(OFFSET_KEY, 0);
        saveState(TOTAL_KEY, 0);
        startSync();
    }

    public void healDatabase() {
        if (isHealing) return;

        isHealing = true;
        healTotal = 0;
        healCurrent = 0;

        log.info("🛠️ DÉBUT DE LA RÉPARATION DES DONNÉES...");
        stopSync();

        try {
            log.info("🧹 Début du Smart Clean...");
            int totalDeleted = 0;
            while (true) {
                List<Long> duplicateIds = interventionRepository.findDuplicateIds();
                if (duplicateIds.isEmpty()) break;
                interventionRepository.deleteLogsByIds(duplicateIds);
                int deleted = interventionRepository.deleteInterventionsByIds(duplicateIds);
                totalDeleted += deleted;
            }
            log.info("🧹 Smart Clean terminé ! Doublons supprimés : {}", totalDeleted);

            List<Intervention> brokenInterventions = interventionRepository.findInterventionsWithMissingDetails();
            healTotal = brokenInterventions.size();
            log.info("🔍 {} interventions trouvées sans détails. Début de la récupération...", healTotal);

            if (healTotal == 0) {
                isHealing = false;
                return;
            }

            int chunkSize = 50;
            for (int i = 0; i < brokenInterventions.size(); i += chunkSize) {
                if (!isHealing) break;

                List<Intervention> chunk = brokenInterventions.subList(i, Math.min(i + chunkSize, brokenInterventions.size()));
                List<String> idsToHeal = chunk.stream().map(Intervention::getIdIntervention).toList();

                boolean success = false;
                for (int attempt = 1; attempt <= 5; attempt++) {
                    try {
                        Map<String, Object> response = phpApiClient.healData(idsToHeal);

                        if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                            Object rawData = response.get("data");
                            Map<String, String> healedData = new HashMap<>();

                            if (rawData instanceof Map) {
                                healedData = (Map<String, String>) rawData;
                            }

                            for (Intervention inv : chunk) {
                                String detailStr = healedData.get(inv.getIdIntervention());
                                if (detailStr != null) {
                                    inv.setDetailIntervention(detailStr);
                                }
                            }
                            interventionRepository.saveAll(chunk);

                            healCurrent += chunk.size();
                            success = true;
                            Thread.sleep(1000);
                            break;
                        }
                    } catch (Exception e) {
                        Thread.sleep(5000);
                    }
                }

                if (!success) {
                    healCurrent += chunk.size();
                }
            }
            log.info("🎉 RÉPARATION TERMINÉE !");

        } catch (Exception e) {
            log.error("❌ Erreur fatale pendant la réparation : ", e);
        } finally {
            isHealing = false;
        }
    }

    private void processLoop() {
        while (isRunning) {
            try {
                // 1. L'ASPIRATEUR : Ymessa7 Ionos kamel w y-sauvi 3ndna f DW b chkel safe
                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    ExportResponse exportResp = phpApiClient.export(BATCH_SIZE);

                    if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                        List<Intervention> incomingData = exportResp.getData();

                        List<Long> idsToAck = incomingData.stream()
                                .map(Intervention::getId)
                                .filter(id -> id != null && id > 0)
                                .collect(Collectors.toList());

                        transactionTemplate.executeWithoutResult(status -> {
                            List<String> incomingEpsIds = incomingData.stream()
                                    .map(Intervention::getIdIntervention)
                                    .filter(id -> id != null && !id.isEmpty())
                                    .toList();

                            List<Intervention> existingData = interventionRepository.findByIdInterventionIn(incomingEpsIds);
                            Map<String, Intervention> existingMap = existingData.stream()
                                    .collect(Collectors.toMap(Intervention::getIdIntervention, i -> i, (i1, i2) -> i1));

                            for (Intervention incoming : incomingData) {
                                if (incoming.getIdIntervention() == null || incoming.getIdIntervention().isEmpty()) continue;

                                Intervention existing = existingMap.get(incoming.getIdIntervention());

                                if (existing == null) {
                                    existing = incoming;
                                    existing.setId(null);
                                    if (existing.getActionsLog() != null) {
                                        for (ActionLog log : existing.getActionsLog()) {
                                            log.setId(null);
                                        }
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

                                    if (existing.getActionsLog() != null) {
                                        existing.getActionsLog().clear();
                                    } else {
                                        existing.setActionsLog(new ArrayList<>());
                                    }

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
                            phpApiClient.acknowledge(idsToAck);
                        } else {
                            bufferHasData = false;
                        }
                    } else {
                        bufferHasData = false;
                    }
                }

                if (!isRunning) break;

                int currentOffset = getSavedState(OFFSET_KEY);
                int totalApi = getSavedState(TOTAL_KEY);

                if (totalApi > 0 && currentOffset >= totalApi) {
                    log.info("🏁 100% ATTEINT !");
                    isRunning = false;
                    currentEta = "Terminé ✓";
                    break;
                }

                // 2. DEMANDER L'BATCH JDDID MN BOUYGUES (AVEC DETAILS)
                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        ImportResponse importResp = phpApiClient.triggerImport(currentOffset, BATCH_SIZE);

                        if (importResp != null && importResp.isOk()) {
                            if (importResp.getBatchCount() == 0) {
                                isRunning = false;
                                currentEta = "Terminé ✓";
                                break;
                            }

                            saveState(OFFSET_KEY, importResp.getNextOffset());
                            saveState(TOTAL_KEY, importResp.getTotalApi());

                            // CALCUL DE L'ETA
                            totalProcessedSinceStart += importResp.getBatchCount();
                            long elapsedMillis = System.currentTimeMillis() - syncStartTime;
                            if (totalProcessedSinceStart > 0) {
                                long millisPerItem = elapsedMillis / totalProcessedSinceStart;
                                int remainingItems = importResp.getTotalApi() - importResp.getNextOffset();
                                long remainingMillis = remainingItems * millisPerItem;
                                currentEta = formatDuration(remainingMillis);
                            }

                            log.info("📥 Import BT : Offset {} -> {}. ETA: {}",
                                    currentOffset, importResp.getNextOffset(), currentEta);

                            importSuccess = true;
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ Erreur Import BT (Tentative {}/3). Pause de 10s...", attempt);
                        Thread.sleep(10000);
                    }
                }

                if (!importSuccess && isRunning) {
                    Thread.sleep(30000);
                }

            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
        log.info("⏹️ BOUCLE ARRÊTÉE.");
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).orElse(0);
    }

    private void saveState(String key, int value) {
        syncStateRepository.save(new SyncState(key, value));
    }
}