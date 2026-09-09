package com.kyntus.gringotts_sync.controller;

import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import com.kyntus.gringotts_sync.service.SyncOrchestrator;
import com.kyntus.gringotts_sync.service.ExportExcelService; // 🛡️ JDID
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.YearMonth;
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
    private final ExportExcelService exportExcelService; // 🛡️ JDID

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_interventions_local", interventionRepository.count());

        stats.put("current_bt_offset", syncStateRepository.findById("bt_api_offset").map(SyncState::getStateValue).orElse(0));
        stats.put("total_api", syncStateRepository.findById("bt_total_api").map(SyncState::getStateValue).orElse(0));

        stats.put("period_offset", syncStateRepository.findById("bt_api_offset_period").map(SyncState::getStateValue).orElse(0));
        stats.put("period_total", syncStateRepository.findById("bt_total_api_period").map(SyncState::getStateValue).orElse(0));
        stats.put("period_processed_total", syncOrchestrator.getTotalPeriodProcessed());
        stats.put("current_period", syncOrchestrator.getCurrentPeriod());
        stats.put("saved_period", syncStateRepository.findById("bt_active_period_name").map(SyncState::getStateValueStr).orElse(null));

        stats.put("is_running", syncOrchestrator.isRunning());
        stats.put("eta", syncOrchestrator.getCurrentEta());
        stats.put("is_healing", syncOrchestrator.isHealing());
        stats.put("healer_mode", syncOrchestrator.getHealerMode());
        stats.put("heal_total", syncOrchestrator.getHealTotal());
        stats.put("heal_current", syncOrchestrator.getHealCurrent());
        stats.put("radar_status", syncOrchestrator.getRadarStatus());
        stats.put("healer_status", syncOrchestrator.getHealerStatus());
        stats.put("alerts", syncOrchestrator.getRecentAlerts());
        stats.put("radar_processed_total", syncOrchestrator.getTotalRadarProcessed());
        stats.put("healer_processed_total", syncOrchestrator.getTotalHealerProcessed());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/interventions")
    public ResponseEntity<Page<Intervention>> getInterventions(
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "ALL") String source,
            @RequestParam(required = false, defaultValue = "") String period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        String cleanPeriod = "";
        if (period != null && !period.isEmpty()) {
            cleanPeriod = period.replace("_", "-").replace("-M", "-M");
        }

        Page<Intervention> result = interventionRepository.findFilteredInterventions(
                search == null ? "" : search,
                source == null ? "ALL" : source,
                cleanPeriod,
                PageRequest.of(page, size)
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false, defaultValue = "ALL") String source,
            @RequestParam(required = false) String period,
            @RequestParam(required = false, defaultValue = "ALL") String type) {

        try {
            byte[] excelData = exportExcelService.generateExcelExport(source, period, type);

            String filename = "Export_Gringotts_" + (type.equals("ALL") ? "Global" : type) +
                    "_" + (source.equals("ALL") ? "ToutesSources" : source) +
                    ((period == null || period.isEmpty()) ? "" : "_" + period) + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelData);
        } catch (Throwable t) {
            // 🛡️ L'FIX HNA : On attrape TOUT (même les OutOfMemoryError) et on logge en ROUGE
            System.err.println("❌ ERREUR CRITIQUE DANS LE CONTROLEUR EXPORT : " + t.getMessage());
            t.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
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

    @PostMapping("/start-healer")
    public ResponseEntity<Map<String, String>> startHealer(@RequestBody Map<String, String> body) {
        String mode = body.getOrDefault("mode", "RADAR");
        syncOrchestrator.startHealer(mode);
        return ResponseEntity.ok(Map.of("message", "Healer démarré en mode " + mode));
    }

    @PostMapping("/stop-healer")
    public ResponseEntity<Map<String, String>> stopHealer() {
        syncOrchestrator.stopHealer();
        return ResponseEntity.ok(Map.of("message", "Healer arrêté."));
    }

    @PostMapping("/cancel-resume")
    public ResponseEntity<Map<String, String>> cancelResume() {
        syncOrchestrator.cancelResume();
        return ResponseEntity.ok(Map.of("message", "Session annulée."));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetSync() {
        new Thread(syncOrchestrator::purgeDatabase).start();
        return ResponseEntity.ok(Map.of("message", "Purge en cours..."));
    }

    @PostMapping("/clean-duplicates")
    public ResponseEntity<Map<String, Object>> cleanDuplicates() {
        new Thread(syncOrchestrator::cleanDuplicatesTask).start();
        return ResponseEntity.ok(Map.of("ok", true, "message", "Nettoyage lancé en arrière-plan."));
    }

    @PostMapping("/trim/{keepCount}")
    public ResponseEntity<Map<String, Object>> trimDatabase(@PathVariable int keepCount) {
        int deleted = interventionRepository.deleteExcessRecords(keepCount);
        return ResponseEntity.ok(Map.of("ok", true, "message", deleted + " anciennes interventions supprimées."));
    }

    @PostMapping("/offset/{value}")
    public ResponseEntity<Map<String, Object>> setManualOffset(@PathVariable int value) {
        if (syncOrchestrator.getCurrentPeriod() != null || syncStateRepository.findById("bt_active_period_name").map(SyncState::getStateValueStr).orElse("").length() > 0) {
            SyncState state = syncStateRepository.findById("bt_api_offset_period").orElse(new SyncState("bt_api_offset_period", value, null));
            state.setStateValue(value);
            syncStateRepository.save(state);
        } else {
            SyncState state = syncStateRepository.findById("bt_api_offset").orElse(new SyncState("bt_api_offset", value, null));
            state.setStateValue(value);
            syncStateRepository.save(state);
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "Offset forcé à " + value));
    }
}