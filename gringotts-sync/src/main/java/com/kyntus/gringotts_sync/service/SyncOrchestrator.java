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
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private volatile boolean isRunning = false;
    private volatile boolean isHealing = false;
    private volatile int healTotal = 0;
    private volatile int healCurrent = 0;

    // VARIABLES POUR L'ETA
    private volatile long syncStartTime = 0;
    private volatile int totalProcessedSinceStart = 0;
    private volatile String currentEta = "Calcul en cours...";

    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";
    // 🚀 L'BATCH kber (500) hit Java ghaytkellef b l'Multithreading l-dakhel
    private static final int BATCH_SIZE = 500;

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
        log.info("🚀 DÉMARRAGE DU MODE V8 TWIN-TURBO ({} PAR BATCH - THREADS PARALLELES)", BATCH_SIZE);
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
            List<Intervention> brokenInterventions = interventionRepository.findInterventionsWithMissingDetails();
            healTotal = brokenInterventions.size();
            log.info("🔍 {} interventions trouvées sans détails. Début de la récupération...", healTotal);

            if (healTotal == 0) {
                isHealing = false;
                return;
            }

            List<String> idsToHeal = brokenInterventions.stream().map(Intervention::getIdIntervention).toList();
            Map<String, String> fetchedDetails = fetchDetailsConcurrently(idsToHeal, true);

            for (Intervention inv : brokenInterventions) {
                String d = fetchedDetails.get(inv.getIdIntervention());
                if (d != null) inv.setDetailIntervention(d);
            }
            interventionRepository.saveAll(brokenInterventions);
            log.info("🎉 RÉPARATION TERMINÉE !");

        } catch (Exception e) {
            log.error("❌ Erreur fatale pendant la réparation : ", e);
        } finally {
            isHealing = false;
        }
    }

    // 💡 L'IDÉE BRILLANTE : MULTITHREADING F JAVA BACH N-BYPASSIW AKAMAI
    private Map<String, String> fetchDetailsConcurrently(List<String> ids, boolean isHealingMode) {
        Map<String, String> results = new ConcurrentHashMap<>();
        if (ids.isEmpty()) return results;

        int chunkSize = 25; // 25 is safe limit per request
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += chunkSize) {
            chunks.add(ids.subList(i, Math.min(i + chunkSize, ids.size())));
        }

        // 4 Threads x 25 = 100 requêtes max vers Bouygues en parallèle. (Mode furtif 🥷)
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        int totalChunks = chunks.size();
        log.info("🧩 Lancement de 4 Ninja Threads pour récupérer {} sous-lots...", totalChunks);

        for (int i = 0; i < chunks.size(); i++) {
            List<String> chunk = chunks.get(i);
            int chunkIndex = i + 1;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                boolean success = false;
                for (int attempt = 1; attempt <= 5; attempt++) {
                    try {
                        Map<String, Object> response = phpApiClient.healData(chunk);
                        if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                            Object rawData = response.get("data");
                            if (rawData instanceof Map) {
                                Map<String, String> healedData = (Map<String, String>) rawData;
                                results.putAll(healedData);
                            }
                            success = true;
                            if (isHealingMode) {
                                healCurrent += chunk.size();
                            }
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        if (e.getStatusCode().value() == 403) {
                            log.warn("⚠️ [WAF AKAMAI] Blocage 403 sur lot {}/{} (Tentative {}/5). Thread en mode furtif (pause 15s)...", chunkIndex, totalChunks, attempt);
                            sleep(15000);
                        } else {
                            log.warn("⚠️ [ERREUR PHP] HTTP {} sur lot {}/{} : {}", e.getStatusCode(), chunkIndex, totalChunks, e.getResponseBodyAsString());
                            sleep(5000);
                        }
                    } catch (Exception e) {
                        sleep(5000);
                    }
                }
                if (!success) {
                    log.error("❌ Echec définitif pour le sous-lot {}/{} après 5 tentatives.", chunkIndex, totalChunks);
                    if (isHealingMode) healCurrent += chunk.size();
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        return results;
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
    }

    private void processLoop() {
        while (isRunning) {
            try {
                // 1. L'ASPIRATEUR & FETCH DES DÉTAILS
                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    try {
                        ExportResponse exportResp = phpApiClient.export(BATCH_SIZE);

                        if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                            List<Intervention> incomingData = exportResp.getData();

                            List<Long> idsToAck = incomingData.stream()
                                    .map(Intervention::getId)
                                    .filter(id -> id != null && id > 0)
                                    .collect(Collectors.toList());

                            // 🚀 FETCH DETAILS ASYNCHRONE AVANT SAUVEGARDE
                            List<String> idsNeedingDetails = incomingData.stream()
                                    .filter(inv -> inv.getDetailIntervention() == null || inv.getDetailIntervention().isEmpty() || inv.getDetailIntervention().equals("null"))
                                    .map(Intervention::getIdIntervention)
                                    .toList();

                            if (!idsNeedingDetails.isEmpty()) {
                                Map<String, String> fetchedDetails = fetchDetailsConcurrently(idsNeedingDetails, false);
                                for (Intervention inv : incomingData) {
                                    String detail = fetchedDetails.get(inv.getIdIntervention());
                                    if (detail != null) {
                                        inv.setDetailIntervention(detail);
                                    }
                                }
                            }

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
                                            for (ActionLog log : existing.getActionsLog()) log.setId(null);
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

                            if (!idsToAck.isEmpty()) phpApiClient.acknowledge(idsToAck);
                            else bufferHasData = false;
                        } else {
                            bufferHasData = false;
                        }
                    } catch (Exception e) {
                        log.error("❌ [ERREUR JAVA/RÉSEAU - EXPORT] : {}", e.getMessage());
                        bufferHasData = false;
                        Thread.sleep(5000);
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

                // 2. IMPORT BT (SANS DÉTAILS - 1 SEULE REQUÊTE ULTRA RAPIDE)
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

                            totalProcessedSinceStart += importResp.getBatchCount();
                            long elapsedMillis = System.currentTimeMillis() - syncStartTime;
                            if (totalProcessedSinceStart > 0) {
                                long millisPerItem = elapsedMillis / totalProcessedSinceStart;
                                int remainingItems = importResp.getTotalApi() - importResp.getNextOffset();
                                long remainingMillis = remainingItems * millisPerItem;
                                currentEta = formatDuration(remainingMillis);
                            }

                            log.info("📥 Liste BT : Offset {} -> {}. ETA: {}", currentOffset, importResp.getNextOffset(), currentEta);
                            importSuccess = true;
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        log.error("🚨 [CRASH API PHP - IMPORT BYTEL] HTTP {} : {}", e.getStatusCode(), e.getResponseBodyAsString());
                        Thread.sleep(10000);
                    } catch (Exception e) {
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