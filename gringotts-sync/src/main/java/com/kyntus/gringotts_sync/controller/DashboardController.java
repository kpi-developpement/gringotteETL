package com.kyntus.gringotts_sync.controller;

import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import com.kyntus.gringotts_sync.service.SyncOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final InterventionRepository interventionRepository;
    private final SyncStateRepository syncStateRepository;
    private final SyncOrchestrator syncOrchestrator;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long totalInterventions = interventionRepository.count();
        int currentOffset = syncStateRepository.findById("bt_api_offset")
                .map(SyncState::getStateValue)
                .orElse(0);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total_interventions_local", totalInterventions);
        stats.put("current_bt_offset", currentOffset);
        stats.put("status", "RUNNING");

        return ResponseEntity.ok(stats);
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerSync() {
        // On lance le cycle manuellement dans un thread séparé pour ne pas bloquer la requête HTTP
        new Thread(syncOrchestrator::runSyncCycle).start();

        Map<String, String> response = new HashMap<>();
        response.put("message", "Cycle de synchronisation lancé avec succès.");
        return ResponseEntity.ok(response);
    }
}