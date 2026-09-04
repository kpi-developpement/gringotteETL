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

    private volatile String currentEta = "En attente...";

    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";
    private static final int BATCH_SIZE = 300;

    public boolean isRunning() { return isRunning; }
    public boolean isHealing() { return isHealing; }
    public int getHealTotal() { return healTotal; }
    public int getHealCurrent() { return healCurrent; }
    public String getCurrentEta() { return currentEta; }

    public void startSync() {
        if (isRunning) return;
        isRunning = true;
        isHealing = true; // Activer le Healer en parallèle
        currentEta = "Initialisation du Radar...";

        log.info("🚀 DÉMARRAGE DU DAEMON 24/7 (RADAR {}/BATCH + HEALER 20/BATCH)", BATCH_SIZE);

        new Thread(this::circularRadarLoop).start();
        new Thread(this::backgroundHealerLoop).start();
    }

    public void stopSync() {
        log.info("🛑 ARRÊT DEMANDÉ PAR L'UTILISATEUR");
        isRunning = false;
        isHealing = false;
        currentEta = "Arrêté";
    }

    public void resetAndStartFromZero() {
        log.info("⚠️ RESET TOTAL DEMANDÉ...");
        stopSync();
        sleep(2000); // Laisser le temps aux threads de mourir
        try { phpApiClient.resetIonos(); } catch (Exception e) {}
        interventionRepository.deleteAll();
        saveState(OFFSET_KEY, 0);
        saveState(TOTAL_KEY, 0);
        startSync();
    }

    // Le bouton Heal Manuel devient "Smart Clean"
    public void healDatabase() {
        log.info("🧹 Lancement du Smart Clean manuel...");
        int totalDeleted = 0;
        while (true) {
            List<Long> duplicateIds = interventionRepository.findDuplicateIds();
            if (duplicateIds.isEmpty()) break;
            interventionRepository.deleteLogsByIds(duplicateIds);
            int deleted = interventionRepository.deleteInterventionsByIds(duplicateIds);
            totalDeleted += deleted;
        }
        log.info("🧹 Smart Clean terminé ! Doublons supprimés : {}", totalDeleted);
    }

    // ==========================================
    // THREAD 1 : LE RADAR CIRCULAIRE (FAST SYNC)
    // ==========================================
    private void circularRadarLoop() {
        while (isRunning) {
            try {
                // 1. Vidage de IONOS vers PostgreSQL
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
                        log.error("❌ [RADAR - IONOS EXPORT] Erreur : {}", e.getMessage());
                        bufferHasData = false;
                        sleep(5000);
                    }
                }

                if (!isRunning) break;

                int currentOffset = getSavedState(OFFSET_KEY);
                int totalApi = getSavedState(TOTAL_KEY);

                // Si on a atteint la fin, le Radar redémarre à 0 pour chercher les mises à jour
                if (totalApi > 0 && currentOffset >= totalApi) {
                    log.info("🔄 CYCLE RADAR TERMINÉ ! Retour à l'offset 0 pour chercher les nouveautés...");
                    saveState(OFFSET_KEY, 0);
                    currentOffset = 0;
                    currentEta = "Nouveau Cycle (Update)";
                    sleep(5000);
                } else {
                    currentEta = "Balayage Continu...";
                }

                // 2. Scan API BT (Liste uniquement - Très rapide)
                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        ImportResponse importResp = phpApiClient.triggerImport(currentOffset, BATCH_SIZE);

                        if (importResp != null && importResp.isOk()) {
                            if (importResp.getBatchCount() == 0) {
                                saveState(OFFSET_KEY, importResp.getTotalApi()); // Force le reset au prochain tour
                                break;
                            }

                            saveState(OFFSET_KEY, importResp.getNextOffset());
                            saveState(TOTAL_KEY, importResp.getTotalApi());

                            log.info("📡 RADAR : Offset {} -> {}. Total: {}", currentOffset, importResp.getNextOffset(), importResp.getTotalApi());
                            importSuccess = true;
                            sleep(500); // 0.5s pause entre chaque scan
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        if (e.getStatusCode().value() == 403) {
                            log.error("🚨 [RADAR] AKAMAI WAF 403 ! Le Radar se met en pause pendant 15 MINUTES.");
                            currentEta = "Pause WAF (15 min)";
                            sleep(15 * 60 * 1000); // Dodo 15 minutes pour purger le ban IP
                        } else {
                            log.warn("⚠️ [RADAR] API PHP HTTP {} : {}", e.getStatusCode(), e.getResponseBodyAsString());
                            sleep(10000);
                        }
                    } catch (Exception e) {
                        sleep(10000);
                    }
                }

                if (!importSuccess && isRunning) sleep(30000);

            } catch (Exception e) {
                sleep(5000);
            }
        }
        log.info("⏹️ RADAR ARRÊTÉ.");
    }

    // ==========================================
    // THREAD 2 : L'ENRICHISSEUR (BACKGROUND HEALER 24/7)
    // ==========================================
    private void backgroundHealerLoop() {
        while (isHealing) {
            try {
                long missingCount = interventionRepository.countInterventionsWithMissingDetails();

                if (missingCount == 0) {
                    healTotal = 0;
                    healCurrent = 0;
                    sleep(30000); // Rien à faire, on dort 30 secondes
                    continue;
                }

                healTotal = (int) missingCount;
                healCurrent = 0;

                // On récupère exactement 20 IDs
                List<Intervention> chunk = interventionRepository.findInterventionsWithMissingDetails();
                if (chunk.isEmpty()) {
                    sleep(5000);
                    continue;
                }

                List<String> idsToHeal = chunk.stream().map(Intervention::getIdIntervention).toList();

                boolean success = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        log.info("🛠️ HEALER : Récupération des détails pour 20 EPS...");
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
                                } else {
                                    // Si Bouygues ne retourne pas le détail, on met "{}" pour ne pas le re-fetcher à l'infini
                                    inv.setDetailIntervention("{}");
                                }
                            }

                            interventionRepository.saveAll(chunk);
                            healCurrent += chunk.size();
                            success = true;
                            sleep(1000); // 🚀 L'FIX HNA : La pause de 1 seconde entre chaque requête
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        if (e.getStatusCode().value() == 403) {
                            log.error("🚨 [HEALER] AKAMAI WAF 403 ! Le Healer se met en pause pendant 15 MINUTES.");
                            sleep(15 * 60 * 1000); // Dodo 15 minutes
                        } else {
                            log.warn("⚠️ [HEALER] HTTP {} : {}", e.getStatusCode(), e.getResponseBodyAsString());
                            sleep(5000);
                        }
                    } catch (Exception e) {
                        sleep(5000);
                    }
                }

                if (!success && isHealing) {
                    for (Intervention inv : chunk) inv.setDetailIntervention("{}");
                    interventionRepository.saveAll(chunk);
                }

            } catch (Exception e) {
                log.error("❌ [HEALER] Erreur : ", e);
                sleep(10000);
            }
        }
        log.info("⏹️ HEALER ARRÊTÉ.");
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
    }

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).orElse(0);
    }

    private void saveState(String key, int value) {
        syncStateRepository.save(new SyncState(key, value));
    }
}