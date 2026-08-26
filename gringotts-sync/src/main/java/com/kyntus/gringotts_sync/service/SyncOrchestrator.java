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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrator {

    private final PhpApiClient phpApiClient;
    private final InterventionRepository interventionRepository;
    private final SyncStateRepository syncStateRepository;

    @Value("${kyntus.sync.batch-size:500}")
    private int batchSize;

    private static final String OFFSET_KEY = "bt_api_offset";

    @Scheduled(cron = "${kyntus.sync.cron}")
    @Transactional
    public void runSyncCycle() {
        try {
            boolean bufferHasData = true;
            int safetyLoopCount = 0;

            // 1. ASPIRATEUR : N-vidiw IONOS kaml 9bel ma n-jbdou jdid
            while (bufferHasData && safetyLoopCount < 10) {
                ExportResponse exportResp = phpApiClient.export(batchSize);

                if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                    List<Intervention> interventions = exportResp.getData();
                    log.info("Aspirateur : Réception de {} interventions depuis IONOS.", interventions.size());

                    // Sauvegarde locale (Ila w93at erreur hna, l'code kay-7bess w ma kay-ms7ch mn IONOS = Zero Data Loss)
                    interventionRepository.saveAll(interventions);

                    // Msi7 mn IONOS
                    List<Long> idsToAck = interventions.stream().map(Intervention::getId).toList();
                    phpApiClient.acknowledge(idsToAck);

                    safetyLoopCount++; // Bach ma n-ti7ouch f boucle infinie ila w9e3 mouchkil
                } else {
                    bufferHasData = false; // IONOS khwa 100%
                }
            }

            // 2. IMPORT : Mnin IONOS khwa, n-goulou lih y-jbed mn Bouygues
            int currentOffset = getSavedOffset();
            log.info("IONOS est vide. Déclenchement Import BT à partir de l'offset {}", currentOffset);

            ImportResponse importResp = phpApiClient.triggerImport(currentOffset);

            if (importResp != null && importResp.isOk()) {
                if (importResp.getBatchCount() > 0) {
                    log.info("Import BT réussi. {} nouvelles lignes dans IONOS.", importResp.getBatchCount());
                    saveOffset(importResp.getNextOffset());
                } else {
                    log.info("Aucune nouvelle donnée chez Bouygues pour le moment.");
                }
            }

        } catch (Exception e) {
            log.error("Erreur pendant le cycle Auto-Sync : {}", e.getMessage());
        }
    }

    private int getSavedOffset() {
        return syncStateRepository.findById(OFFSET_KEY)
                .map(SyncState::getStateValue)
                .orElse(0);
    }

    private void saveOffset(int nextOffset) {
        syncStateRepository.save(new SyncState(OFFSET_KEY, nextOffset));
    }
}