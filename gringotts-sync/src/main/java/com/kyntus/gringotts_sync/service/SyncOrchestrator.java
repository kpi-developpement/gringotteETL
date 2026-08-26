package com.kyntus.gringotts_sync.service;

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

import java.util.List;

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

    public boolean isRunning() {
        return isRunning;
    }

    public void startSync() {
        if (isRunning) return;
        isRunning = true;
        log.info("🚀 DÉMARRAGE DU MODE TURBO CONTINU");
        new Thread(this::processLoop).start();
    }

    public void stopSync() {
        log.info("🛑 ARRÊT DEMANDÉ");
        isRunning = false;
    }

    // 🛡️ NOUVEAU : Fonction de Reset Total
    public void resetAndStartFromZero() {
        log.info("⚠️ RESET TOTAL DEMANDÉ...");
        stopSync();

        // 1. Vider IONOS
        try {
            phpApiClient.resetIonos();
            log.info("✅ IONOS vidé avec succès.");
        } catch (Exception e) {
            log.error("Erreur lors du vidage de IONOS : {}", e.getMessage());
        }

        // 2. Vider la base locale (Postgres)
        interventionRepository.deleteAll();
        log.info("✅ Base de données locale vidée.");

        // 3. Remettre les compteurs à 0
        saveState(OFFSET_KEY, 0);
        saveState(TOTAL_KEY, 0);

        // 4. Lancer l'aspirateur
        startSync();
    }

    private void processLoop() {
        while (isRunning) {
            try {
                // 1. ASPIRATEUR : On vide IONOS
                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    ExportResponse exportResp = phpApiClient.export(500);

                    if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                        List<Intervention> interventions = exportResp.getData();
                        interventionRepository.saveAll(interventions);

                        List<Long> idsToAck = interventions.stream().map(Intervention::getId).toList();
                        phpApiClient.acknowledge(idsToAck);

                        log.info("✅ {} interventions aspirées et supprimées de IONOS.", interventions.size());
                    } else {
                        bufferHasData = false;
                    }
                }

                if (!isRunning) break;

                // 2. IMPORT : On demande à IONOS de ramener depuis Bouygues
                int currentOffset = getSavedState(OFFSET_KEY);
                ImportResponse importResp = phpApiClient.triggerImport(currentOffset);

                if (importResp != null && importResp.isOk()) {
                    saveState(OFFSET_KEY, importResp.getNextOffset());
                    saveState(TOTAL_KEY, importResp.getTotalApi()); // HNA KAN-3ERFOU L'100%

                    log.info("📥 Import BT : Offset {} -> {}. Total dispo : {}",
                            currentOffset, importResp.getNextOffset(), importResp.getTotalApi());

                    if (importResp.isDone() || importResp.getNextOffset() >= importResp.getTotalApi()) {
                        log.info("🏁 TOUTES LES DONNÉES ONT ÉTÉ SYNCHRONISÉES !");
                        isRunning = false;
                    }
                } else {
                    Thread.sleep(3000);
                }

            } catch (Exception e) {
                log.error("❌ Erreur dans la boucle : {}", e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).orElse(0);
    }

    private void saveState(String key, int value) {
        syncStateRepository.save(new SyncState(key, value));
    }
}