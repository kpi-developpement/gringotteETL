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
    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";
    private static final int BATCH_SIZE = 500;

    public boolean isRunning() {
        return isRunning;
    }

    public void startSync() {
        if (isRunning) return;
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
        try {
            phpApiClient.resetIonos();
            log.info("✅ IONOS vidé avec succès.");
        } catch (Exception e) {
            log.error("Erreur lors du vidage de IONOS : {}", e.getMessage());
        }
        interventionRepository.deleteAll();
        log.info("✅ Base de données locale vidée.");
        saveState(OFFSET_KEY, 0);
        saveState(TOTAL_KEY, 0);
        startSync();
    }

    // 🚀 NOUVEAU : La fonction Heal
    public void healDatabase() {
        log.info("🛠️ DÉBUT DE LA RÉPARATION DES DONNÉES...");
        stopSync();

        try {
            int deleted = interventionRepository.deleteDuplicatesSmart();
            log.info("🧹 Smart Clean terminé : {} doublons supprimés.", deleted);

            List<Intervention> brokenInterventions = interventionRepository.findInterventionsWithMissingDetails();
            log.info("🔍 {} interventions trouvées sans détails. Début de la récupération...", brokenInterventions.size());

            int chunkSize = 50;
            ObjectMapper mapper = new ObjectMapper();

            for (int i = 0; i < brokenInterventions.size(); i += chunkSize) {
                List<Intervention> chunk = brokenInterventions.subList(i, Math.min(i + chunkSize, brokenInterventions.size()));
                List<String> idsToHeal = chunk.stream().map(Intervention::getIdIntervention).toList();

                Map<String, Object> response = phpApiClient.healData(idsToHeal);

                if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                    Map<String, Object> healedData = (Map<String, Object>) response.get("data");

                    for (Intervention inv : chunk) {
                        Object detail = healedData.get(inv.getIdIntervention());
                        if (detail != null) {
                            inv.setDetailIntervention(mapper.writeValueAsString(detail));
                        }
                    }
                    interventionRepository.saveAll(chunk);
                    log.info("✅ Paquet réparé et sauvegardé ({} / {})", Math.min(i + chunkSize, brokenInterventions.size()), brokenInterventions.size());
                }
            }
            log.info("🎉 RÉPARATION TERMINÉE AVEC SUCCÈS !");

        } catch (Exception e) {
            log.error("❌ Erreur pendant la réparation : ", e);
        }
    }

    private void processLoop() {
        while (isRunning) {
            try {
                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    ExportResponse exportResp = phpApiClient.export(BATCH_SIZE);

                    if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                        List<Intervention> incomingData = exportResp.getData();
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
                                incoming.setId(null);
                                toSave.add(incoming);
                            }
                        }

                        interventionRepository.saveAll(toSave);

                        List<Long> idsToAck = incomingData.stream().map(Intervention::getId).toList();
                        phpApiClient.acknowledge(idsToAck);

                        log.info("⚡ {} interventions traitées (Insert/Update) et effacées de IONOS.", toSave.size());
                    } else {
                        bufferHasData = false;
                    }
                }

                if (!isRunning) break;

                int currentOffset = getSavedState(OFFSET_KEY);
                int totalApi = getSavedState(TOTAL_KEY);
                long totalLocal = interventionRepository.count();

                if (totalApi > 0 && totalLocal >= totalApi) {
                    log.info("🏁 100% ATTEINT ! Nous avons {} EPS uniques en base. ARRÊT AUTOMATIQUE.", totalLocal);
                    isRunning = false;
                    break;
                }

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

                } else {
                    log.warn("⚠️ Erreur API Bouygues, pause de 2s...");
                    Thread.sleep(2000);
                }

            } catch (Exception e) {
                log.error("❌ Erreur dans la boucle : {}", e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
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