package com.kyntus.gringotts_sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private volatile boolean isRunning = false;
    private volatile boolean isHealing = false;
    private volatile int healTotal = 0;
    private volatile int healCurrent = 0;

    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";
    private static final int BATCH_SIZE = 200;
    private static final int HEAL_CHUNK_SIZE = 20;

    public boolean isRunning() { return isRunning; }
    public boolean isHealing() { return isHealing; }
    public int getHealTotal() { return healTotal; }
    public int getHealCurrent() { return healCurrent; }

    public void startSync() {
        if (isRunning || isHealing) return;
        isRunning = true;
        log.info("🚀 DÉMARRAGE DU MODE TURBO (LOGIQUE JAVA)");
        new Thread(this::processLoop).start();
    }

    public void stopSync() {
        log.info("🛑 ARRÊT DEMANDÉ PAR L'UTILISATEUR");
        isRunning = false;
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
            log.info("🧹 Début du Smart Clean (Suppression des doublons par lots)...");
            int totalDeleted = 0;
            while (true) {
                List<Long> duplicateIds = interventionRepository.findDuplicateIds();
                if (duplicateIds.isEmpty()) {
                    break;
                }
                interventionRepository.deleteLogsByIds(duplicateIds);
                int deleted = interventionRepository.deleteInterventionsByIds(duplicateIds);
                totalDeleted += deleted;
                log.info("   -> {} doublons supprimés (Total: {})", deleted, totalDeleted);
            }
            log.info("🧹 Smart Clean terminé ! Total des doublons supprimés : {}", totalDeleted);

            List<Intervention> brokenInterventions = interventionRepository.findInterventionsWithMissingDetails();
            healTotal = brokenInterventions.size();
            log.info("🔍 {} interventions trouvées sans détails. Début de la récupération...", healTotal);

            if (healTotal == 0) {
                isHealing = false;
                return;
            }

            int chunkSize = 50;
            ObjectMapper mapper = new ObjectMapper();

            for (int i = 0; i < brokenInterventions.size(); i += chunkSize) {
                if (!isHealing) break;

                List<Intervention> chunk = brokenInterventions.subList(i, Math.min(i + chunkSize, brokenInterventions.size()));
                List<String> idsToHeal = chunk.stream().map(Intervention::getIdIntervention).toList();

                boolean success = false;
                int maxRetries = 5;

                for (int attempt = 1; attempt <= maxRetries; attempt++) {
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
                            log.info("✅ Paquet réparé ({} / {})", healCurrent, healTotal);
                            success = true;

                            Thread.sleep(1000);
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ Erreur API Bouygues (Tentative {}/{}) : {}. Pause de 10s...", attempt, maxRetries, e.getMessage());
                        Thread.sleep(10000);
                    }
                }

                if (!success) {
                    log.error("❌ Impossible de réparer ce paquet après {} tentatives. On passe au suivant pour ne pas bloquer le système.", maxRetries);
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
                // 1. ASPIRATEUR
                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    ExportResponse exportResp = phpApiClient.export(BATCH_SIZE);

                    if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                        List<Intervention> incomingData = exportResp.getData();

                        // 🚀 L'FIX HNA : On extrait les IDs de IONOS AVANT de les modifier !
                        List<Long> idsToAck = incomingData.stream()
                                .map(Intervention::getId)
                                .filter(id -> id != null)
                                .toList();

                        List<String> incomingEpsIds = incomingData.stream().map(Intervention::getIdIntervention).toList();

                        List<Intervention> existingData = interventionRepository.findByIdInterventionIn(incomingEpsIds);
                        Map<String, Intervention> existingMap = existingData.stream()
                                .collect(Collectors.toMap(Intervention::getIdIntervention, i -> i, (i1, i2) -> i1));

                        List<Intervention> toSave = new ArrayList<>();

                        for (Intervention incoming : incomingData) {
                            Intervention existing = existingMap.get(incoming.getIdIntervention());
                            if (existing != null) {
                                existing.setEtat(incoming.getEtat());
                                existing.setDateModificationEtat(incoming.getDateModificationEtat());
                                existing.setDetailIntervention(incoming.getDetailIntervention());
                                existing.setPayloadRecu(incoming.getPayloadRecu());
                                toSave.add(existing);
                            } else {
                                // Maintenant on peut mettre à null en toute sécurité pour Postgres
                                incoming.setId(null);
                                toSave.add(incoming);
                            }
                        }

                        interventionRepository.saveAll(toSave);

                        // On envoie les vrais IDs à PHP pour les supprimer
                        if (!idsToAck.isEmpty()) {
                            phpApiClient.acknowledge(idsToAck);
                        }

                        log.info("⚡ {} interventions traitées (Insert/Update) et effacées de IONOS.", toSave.size());
                    } else {
                        bufferHasData = false;
                    }
                }

                if (!isRunning) break;

                int currentOffset = getSavedState(OFFSET_KEY);
                int totalApi = getSavedState(TOTAL_KEY);
                long totalLocal = interventionRepository.count();

                if (totalApi > 0 && currentOffset >= totalApi) {
                    log.info("🏁 100% ATTEINT ! L'Offset a parcouru toutes les données de Bouygues. ARRÊT AUTOMATIQUE.");
                    isRunning = false;
                    break;
                }

                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        ImportResponse importResp = phpApiClient.triggerImport(currentOffset, BATCH_SIZE);

                        if (importResp != null && importResp.isOk()) {
                            if (importResp.getBatchCount() == 0) {
                                log.info("🏁 API BOUYGUES A RETOURNÉ 0 RÉSULTATS. ARRÊT AUTOMATIQUE.");
                                isRunning = false;
                                break;
                            }

                            saveState(OFFSET_KEY, importResp.getNextOffset());
                            saveState(TOTAL_KEY, importResp.getTotalApi());

                            log.info("📥 Import BT : Offset {} -> {}. Total dispo : {}",
                                    currentOffset, importResp.getNextOffset(), importResp.getTotalApi());

                            importSuccess = true;
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ Erreur Import BT (Tentative {}/3) : {}. Pause de 10s...", attempt, e.getMessage());
                        Thread.sleep(10000);
                    }
                }

                if (!importSuccess && isRunning) {
                    log.error("❌ Échec de l'import après 3 tentatives. Pause de 30s avant le prochain cycle...");
                    Thread.sleep(30000);
                }

            } catch (Exception e) {
                log.error("❌ Erreur générale dans la boucle : {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
        log.info("⏹️ BOUCLE ARRÊTÉE.");
    }

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).orElse(0);
    }

    private void saveState(String key, int value) {
        syncStateRepository.save(new SyncState(key, value));
    }
}