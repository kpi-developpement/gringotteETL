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

    @Value("${kyntus.sync.batch-size:200}")
    private int batchSize;

    private static final String OFFSET_KEY = "bt_api_offset";

    /**
     * Cette méthode s'exécute automatiquement selon le Cron défini dans application.yml.
     */
    @Scheduled(cron = "${kyntus.sync.cron}")
    @Transactional
    public void runSyncCycle() {
        log.info("=== DÉBUT DU CYCLE DE SYNCHRONISATION ===");

        try {
            // ÉTAPE 1 : Vider le buffer (IONOS -> Spring Boot)
            ExportResponse exportResp = phpApiClient.export(batchSize);

            if (exportResp != null && exportResp.isOk() && exportResp.getCount() > 0) {
                List<Intervention> interventions = exportResp.getData();
                log.info("Réception de {} interventions depuis le buffer IONOS.", interventions.size());

                // ÉTAPE 2 : Sauvegarder dans la DB locale (Data Warehouse)
                interventionRepository.saveAll(interventions);
                log.info("Sauvegarde locale réussie.");

                // ÉTAPE 3 : Acquitter (Supprimer de IONOS)
                List<Long> idsToAck = interventions.stream().map(Intervention::getId).toList();
                phpApiClient.acknowledge(idsToAck);
                log.info("Acquittement envoyé à IONOS pour libérer l'espace.");

            } else {
                log.info("Le buffer IONOS est vide. Déclenchement de l'import depuis Bouygues...");

                // ÉTAPE 4 : Remplir le buffer (Bouygues -> IONOS)
                int currentOffset = getSavedOffset();

                ImportResponse importResp = phpApiClient.triggerImport(currentOffset);

                if (importResp != null && importResp.isOk()) {
                    log.info("Import BT réussi. Batch count: {}, Inserted: {}, Updated: {}",
                            importResp.getBatchCount(), importResp.getInserted(), importResp.getUpdated());

                    // On met à jour l'offset pour le prochain coup
                    saveOffset(importResp.getNextOffset());

                    if (importResp.isDone()) {
                        log.info("L'API Bouygues a signalé la fin des données (done=true).");
                        // Optionnel : tu pourrais remettre l'offset à 0 ici si tu veux recommencer depuis le début
                        // saveOffset(0);
                    }
                } else {
                    log.error("Erreur lors du déclenchement de l'import: {}", importResp != null ? importResp.getError() : "null");
                }
            }

        } catch (Exception e) {
            log.error("Erreur critique pendant le cycle de synchronisation : {}", e.getMessage(), e);
        }

        log.info("=== FIN DU CYCLE DE SYNCHRONISATION ===\n");
    }

    // --- Méthodes utilitaires pour gérer l'Offset ---

    private int getSavedOffset() {
        return syncStateRepository.findById(OFFSET_KEY)
                .map(SyncState::getStateValue)
                .orElse(0); // Si pas trouvé, on commence à 0
    }

    private void saveOffset(int nextOffset) {
        syncStateRepository.save(new SyncState(OFFSET_KEY, nextOffset));
        log.info("Nouvel offset sauvegardé : {}", nextOffset);
    }
}