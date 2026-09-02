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
        int currentOffset = syncStateRepository.findById("bt_api_offset").map(SyncState::getStateValue).orElse(0);
        int totalApi = syncStateRepository.findById("bt_total_api").map(SyncState::getStateValue).orElse(0);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total_interventions_local", totalInterventions);
        stats.put("current_bt_offset", currentOffset);
        stats.put("total_api", totalApi);
        stats.put("is_running", syncOrchestrator.isRunning());

        return ResponseEntity.ok(stats);
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startSync() {
        syncOrchestrator.startSync();
        return ResponseEntity.ok(Map.of("message", "Démarré."));
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

    @PostMapping("/offset/{value}")
    public ResponseEntity<Map<String, String>> setOffset(@PathVariable int value) {
        syncStateRepository.save(new SyncState("bt_api_offset", value));
        return ResponseEntity.ok(Map.of("message", "Offset mis à jour à " + value));
    }

    @PostMapping("/clean-duplicates")
    public ResponseEntity<Map<String, Object>> cleanDuplicates() {
        int deletedCount = interventionRepository.deleteDuplicates();
        return ResponseEntity.ok(Map.of("ok", true, "message", deletedCount + " doublons supprimés avec succès."));
    }

    // 🚀 NOUVEAU : Endpoint pour couper la base de données
    @PostMapping("/trim/{keepCount}")
    public ResponseEntity<Map<String, Object>> trimDatabase(@PathVariable int keepCount) {
        int offset = keepCount - 1;
        if (offset < 0) offset = 0;
        int deletedCount = interventionRepository.deleteExcessRecords(offset);
        return ResponseEntity.ok(Map.of("ok", true, "message", deletedCount + " enregistrements excédentaires supprimés."));
    }

    @GetMapping("/interventions")
    public ResponseEntity<Page<Intervention>> getInterventions(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Intervention> result = interventionRepository.findByIdInterventionContainingIgnoreCaseOrderByCreatedAtDesc(search, pageable);
        return ResponseEntity.ok(result);
    }
}