package com.kyntus.gringotts_sync.controller;

import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import com.kyntus.gringotts_sync.service.SyncOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_interventions_local", interventionRepository.count());

        // Mode Standard
        stats.put("current_bt_offset", syncStateRepository.findById("bt_api_offset").map(SyncState::getStateValue).orElse(0));
        stats.put("total_api", syncStateRepository.findById("bt_total_api").map(SyncState::getStateValue).orElse(0));

        // Mode Time Machine
        stats.put("period_offset", syncStateRepository.findById("bt_api_offset_period").map(SyncState::getStateValue).orElse(0));
        stats.put("period_total", syncStateRepository.findById("bt_total_api_period").map(SyncState::getStateValue).orElse(0));
        stats.put("period_processed_total", syncOrchestrator.getTotalPeriodProcessed());
        stats.put("current_period", syncOrchestrator.getCurrentPeriod());

        stats.put("is_running", syncOrchestrator.isRunning());
        stats.put("eta", syncOrchestrator.getCurrentEta());
        stats.put("is_healing", syncOrchestrator.isHealing());
        stats.put("heal_total", syncOrchestrator.getHealTotal());
        stats.put("heal_current", syncOrchestrator.getHealCurrent());
        stats.put("radar_status", syncOrchestrator.getRadarStatus());
        stats.put("healer_status", syncOrchestrator.getHealerStatus());
        stats.put("alerts", syncOrchestrator.getRecentAlerts());
        stats.put("radar_processed_total", syncOrchestrator.getTotalRadarProcessed());
        stats.put("healer_processed_total", syncOrchestrator.getTotalHealerProcessed());

        return ResponseEntity.ok(stats);
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startSync() {
        syncOrchestrator.startSync();
        return ResponseEntity.ok(Map.of("message", "Démarré."));
    }

    @PostMapping("/start-periods")
    public ResponseEntity<Map<String, String>> startPeriods(@RequestBody Map<String, String> body) {
        String periodsStr = body.get("periods");
        if (periodsStr == null || periodsStr.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aucune période."));
        }
        List<String> periods = Arrays.stream(periodsStr.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        syncOrchestrator.startPeriodSync(periods);
        return ResponseEntity.ok(Map.of("message", "Sync par période démarrée"));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopSync() {
        syncOrchestrator.stopSync();
        return ResponseEntity.ok(Map.of("message", "Arrêté."));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetSync() {
        new Thread(syncOrchestrator::resetAndStartFromZero).start();
        return ResponseEntity.ok(Map.of("message", "Reset en cours..."));
    }

    @PostMapping("/heal")
    public ResponseEntity<Map<String, String>> healData() {
        new Thread(syncOrchestrator::healDatabase).start();
        return ResponseEntity.ok(Map.of("message", "Processus lancé."));
    }

    @PostMapping("/clean-duplicates")
    public ResponseEntity<Map<String, Object>> cleanDuplicates() {
        return ResponseEntity.ok(Map.of("ok", true, "message", interventionRepository.deleteDuplicates() + " doublons supprimés."));
    }
}