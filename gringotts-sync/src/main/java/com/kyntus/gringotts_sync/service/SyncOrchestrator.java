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

    private void processLoop() {
        while (isRunning) {
            try {
                // 1. ASPIRATEUR
                boolean bufferHasData = true;
                while (bufferHasData && isRunning) {
                    ExportResponse exportResp = phpApiClient.export(500);

                    if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                        List<Intervention> interventions = exportResp.getData();
                        interventionRepository.saveAll(interventions);
                        
                        // 🛡️ L'FIX HNA : N-t2ekdou bli les IDs kaynin w machi null 9bel ma n-siftouhom
                        List<Long> idsToAck = interventions.stream()
                                .map(Intervention::getId)
                                .filter(id -> id != null)
                                .toList();
                                
                        if (!idsToAck.isEmpty()) {
                            phpApiClient.acknowledge(idsToAck);
                            log.info("✅ {} interventions aspirées et supprimées de IONOS.", idsToAck.size());
                        } else {
                            log.warn("⚠️ Data reçue mais aucun ID valide trouvé. Arrêt de l'aspiration.");
                            bufferHasData = false;
                        }
                    } else {
                        bufferHasData = false;
                    }
                }

                if (!isRunning) break;

                // 2. IMPORT
                int currentOffset = getSavedState(OFFSET_KEY);
                ImportResponse importResp = phpApiClient.triggerImport(currentOffset);
                
                if (importResp != null && importResp.isOk()) {
                    saveState(OFFSET_KEY, importResp.getNextOffset());
                    saveState(TOTAL_KEY, importResp.getTotalApi());
                    
                    log.info("📥 Import BT : Offset {} -> {}. Total dispo : {}", 
                            currentOffset, importResp.getNextOffset(), importResp.getTotalApi());

                    if (importResp.isDone() || importResp.getNextOffset() >= importResp.getTotalApi()) {
                        log.info("🏁 TOUTES LES DONNÉES ONT ÉTÉ SYNCHRONISÉES !");
                        isRunning = false;
                    }
                } else {
                    log.warn("⚠️ Erreur lors de l'import BT, pause de 5s...");
                    Thread.sleep(5000);
                }

            } catch (Exception e) {
                log.error("❌ Erreur dans la boucle : {}", e.getMessage());
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