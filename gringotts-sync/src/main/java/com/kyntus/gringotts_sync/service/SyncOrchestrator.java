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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final String OFFSET_KEY = "bt_api_offset";
    private static final String TOTAL_KEY = "bt_total_api";

    // 🚀 L'FIX HNA : Retour à 300 Fixe. L'Auto-scaling a été retiré.
    private static final int IONOS_EXPORT_BATCH = 300;
    private static final int RADAR_BATCH = 300;

    public boolean isRunning() { return isRunning; }
    public boolean isHealing() { return isHealing; }
    public int getHealTotal() { return healTotal; }
    public int getHealCurrent() { return healCurrent; }
    public String getCurrentEta() { return currentEta; }
    public String getRadarStatus() { return radarStatus; }
    public String getHealerStatus() { return healerStatus; }
    public List<String> getRecentAlerts() { return recentAlerts; }

    public long getTotalRadarProcessed() { return totalRadarProcessed; }
    public long getTotalHealerProcessed() { return totalHealerProcessed; }

    private void addAlert(String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        recentAlerts.add(0, "[" + time + "] " + message);
        if (recentAlerts.size() > 20) {
            recentAlerts.remove(recentAlerts.size() - 1);
        }
        log.warn("ALERTE INTERFACE: {}", message);
    }

    public void startSync() {
        if (isRunning) return;
        isRunning = true;
        isHealing = true;
        currentEta = "Initialisation...";
        radarStatus = "Démarrage en cours";
        healerStatus = "Démarrage en cours";
        recentAlerts.clear();
        totalRadarProcessed = 0;
        totalHealerProcessed = 0;

        addAlert("[SYSTEM] Démarrage du Daemon 24/7 (Radar à 300 Fixe)");

        new Thread(this::circularRadarLoop).start();
        new Thread(this::backgroundHealerLoop).start();
    }

    public void stopSync() {
        isRunning = false;
        isHealing = false;
        currentEta = "Arrêté";
        radarStatus = "Arrêt demandé";
        healerStatus = "Arrêt demandé";
        addAlert("[SYSTEM] Arrêt du système demandé par l'utilisateur");
    }

    public void resetAndStartFromZero() {
        stopSync();
        sleep(2000);
        try { phpApiClient.resetIonos(); } catch (Exception e) {}
        interventionRepository.deleteAll();
        saveState(OFFSET_KEY, 0);
        saveState(TOTAL_KEY, 0);
        totalRadarProcessed = 0;
        totalHealerProcessed = 0;
        addAlert("[SYSTEM] Base de données IONOS et Locale réinitialisées");
        startSync();
    }

    public void healDatabase() {
        addAlert("[MAINTENANCE] Smart Clean (Nettoyage doublons) lancé");
        int totalDeleted = 0;
        while (true) {
            List<Long> duplicateIds = interventionRepository.findDuplicateIds();
            if (duplicateIds.isEmpty()) break;
            interventionRepository.deleteLogsByIds(duplicateIds);
            int deleted = interventionRepository.deleteInterventionsByIds(duplicateIds);
            totalDeleted += deleted;
        }
        addAlert("[MAINTENANCE] Smart Clean terminé. " + totalDeleted + " doublons supprimés.");
    }

    private void circularRadarLoop() {
        radarStatus = "En cours d'aspiration";
        while (isRunning) {
            try {
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
                        radarStatus = "Erreur Vidage IONOS";
                        bufferHasData = false;
                        sleep(5000);
                    }
                }

                if (!isRunning) break;

                int currentOffset = getSavedState(OFFSET_KEY);
                int totalApi = getSavedState(TOTAL_KEY);

                if (totalApi > 0 && currentOffset >= totalApi) {
                    saveState(OFFSET_KEY, 0);
                    currentOffset = 0;
                    currentEta = "Nouveau Cycle";
                    radarStatus = "Cycle 100% terminé. Pause 30s.";
                    sleep(30000);
                } else {
                    radarStatus = "Scan Bouygues en cours...";
                }

                boolean importSuccess = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        // 🚀 On utilise le RADAR_BATCH fixe à 300
                        ImportResponse importResp = phpApiClient.triggerImport(currentOffset, RADAR_BATCH);

                        if (importResp != null && importResp.isOk()) {
                            if (importResp.getBatchCount() == 0) {
                                saveState(OFFSET_KEY, importResp.getTotalApi());
                                break;
                            }
                            saveState(OFFSET_KEY, importResp.getNextOffset());
                            saveState(TOTAL_KEY, importResp.getTotalApi());

                            totalRadarProcessed += importResp.getBatchCount();
                            totalProcessedSinceStart += importResp.getBatchCount();

                            if (totalProcessedSinceStart > 0 && syncStartTime > 0) {
                                long elapsedMillis = System.currentTimeMillis() - syncStartTime;
                                long millisPerItem = elapsedMillis / totalProcessedSinceStart;
                                int remainingItems = importResp.getTotalApi() - importResp.getNextOffset();
                                currentEta = formatDuration(remainingItems * millisPerItem);
                            }
                            importSuccess = true;
                            radarStatus = "Vitesse: " + RADAR_BATCH + " EPS (Offset: " + importResp.getNextOffset() + ")";
                            sleep(2000);
                            break;
                        }
                    } catch (RestClientResponseException e) {
                        String body = e.getResponseBodyAsString();
                        if (e.getStatusCode().value() == 403 || body.contains("Access Denied") || body.contains("403")) {
                            addAlert("[RADAR] Bloqué par le Pare-feu Bouygues (Akamai). Veille 15m.");
                            radarStatus = "Banni (Pause 15 min)";
                            currentEta = "Pause WAF";
                            sleep(15 * 60 * 1000);
                        }
                        else if (e.getStatusCode().value() == 500 || e.getStatusCode().value() == 504 || body.contains("GatewayTimeout")) {
                            addAlert("[RADAR] Serveur Bouygues Surchargé (HTTP " + e.getStatusCode() + ") - Retry...");
                            radarStatus = "Erreur HTTP " + e.getStatusCode() + " - Retry...";
                            sleep(15000); // Pause prolongée de 15s pour laisser Bouygues digérer le gros Offset
                        }
                        else {
                            addAlert("[RADAR] Erreur Inconnue (HTTP " + e.getStatusCode() + ")");
                            radarStatus = "Erreur HTTP " + e.getStatusCode();
                            sleep(10000);
                        }
                    } catch (Exception e) {
                        radarStatus = "Erreur Connexion";
                        sleep(10000);
                    }
                }

                if (!importSuccess && isRunning) {
                    radarStatus = "Échecs répétés, pause 30s";
                    sleep(30000);
                }

            } catch (Exception e) {
                radarStatus = "Erreur Critique";
                sleep(5000);
            }
        }
        radarStatus = "Arrêté";
    }

    private void backgroundHealerLoop() {
        healerStatus = "En veille";
        while (isHealing) {
            try {
                long missingCount = interventionRepository.countInterventionsWithMissingDetails();

                if (missingCount == 0) {
                    healTotal = 0;
                    healCurrent = 0;
                    healerStatus = "Base 100% à jour (Veille)";
                    sleep(10000);
                    continue;
                }

                healTotal = (int) missingCount;
                healCurrent = 0;

                List<Intervention> chunk = interventionRepository.findInterventionsWithMissingDetails();
                if (chunk.isEmpty()) {
                    sleep(5000);
                    continue;
                }

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
                        if (e.getStatusCode().value() == 403 || body.contains("Access Denied") || body.contains("403")) {
                            addAlert("[HEALER] Pare-feu Bouygues déclenché. Veille 15m.");
                            healerStatus = "Banni (Pause 15 min)";
                            sleep(15 * 60 * 1000);
                        } else {
                            healerStatus = "Erreur HTTP " + e.getStatusCode();
                            sleep(5000);
                        }
                    } catch (Exception e) {
                        healerStatus = "Erreur Connexion";
                        sleep(5000);
                    }
                }

                if (!success && isHealing) {
                    for (Intervention inv : chunk) inv.setDetailIntervention("{}");
                    interventionRepository.saveAll(chunk);
                }

            } catch (Exception e) {
                healerStatus = "Erreur Critique";
                sleep(10000);
            }
        }
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

    private int getSavedState(String key) {
        return syncStateRepository.findById(key).map(SyncState::getStateValue).orElse(0);
    }

    private void saveState(String key, int value) {
        syncStateRepository.save(new SyncState(key, value));
    }
}