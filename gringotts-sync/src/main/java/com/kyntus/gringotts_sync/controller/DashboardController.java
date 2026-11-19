package com.kyntus.gringotts_sync.controller;

import com.kyntus.gringotts_sync.domain.Intervention;
import com.kyntus.gringotts_sync.domain.SyncState;
import com.kyntus.gringotts_sync.repository.InterventionRepository;
import com.kyntus.gringotts_sync.repository.SyncStateRepository;
import com.kyntus.gringotts_sync.domain.SyncEvent;
import com.kyntus.gringotts_sync.service.ActivityFeed;
import com.kyntus.gringotts_sync.service.AutoPilotService;
import com.kyntus.gringotts_sync.service.DetailBackfillService;
import com.kyntus.gringotts_sync.service.ExplorerService;
import com.kyntus.gringotts_sync.service.EventLogService;
import com.kyntus.gringotts_sync.service.PartenaireApiService;
import com.kyntus.gringotts_sync.service.SyncOrchestrator;
import com.kyntus.gringotts_sync.service.ExportExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private final ExportExcelService exportExcelService;
    private final AutoPilotService autoPilotService;
    private final EventLogService eventLogService;
    private final ActivityFeed activityFeed;
    private final ExplorerService explorerService;
    private final DetailBackfillService detailBackfillService;
    private final PartenaireApiService partenaireApiService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_interventions_local", interventionRepository.count());

        String currentPeriod = syncOrchestrator.getCurrentPeriod();
        if (currentPeriod != null) {
            stats.put("period_offset", syncStateRepository.findById("offset_" + currentPeriod).map(SyncState::getStateValue).orElse(0));
            stats.put("period_total", syncStateRepository.findById("total_" + currentPeriod).map(SyncState::getStateValue).orElse(0));
        } else {
            stats.put("period_offset", 0);
            stats.put("period_total", 0);
        }

        stats.put("period_processed_total", syncOrchestrator.getTotalPeriodProcessed());
        stats.put("current_period", currentPeriod);

        stats.put("is_running", syncOrchestrator.isRunning());
        stats.put("eta", syncOrchestrator.getCurrentEta());
        stats.put("is_healing", syncOrchestrator.isHealing());
        stats.put("healer_mode", syncOrchestrator.getHealerMode());
        stats.put("heal_total", syncOrchestrator.getHealTotal());
        stats.put("heal_current", syncOrchestrator.getHealCurrent());
        stats.put("time_machine_status", syncOrchestrator.getTimeMachineStatus());
        stats.put("healer_status", syncOrchestrator.getHealerStatus());
        stats.put("alerts", syncOrchestrator.getRecentAlerts());
        stats.put("healer_processed_total", syncOrchestrator.getTotalHealerProcessed());
        stats.put("queued_periods", syncOrchestrator.getQueuedPeriods());
        stats.put("autopilot", autoPilotService.describe());
        stats.put("period_inserted", syncOrchestrator.getPeriodInserted());
        stats.put("period_updated", syncOrchestrator.getPeriodUpdated());
        stats.put("period_changed", syncOrchestrator.getPeriodChanged());
        stats.put("event_counts_24h", eventLogService.countsLast24h());
        stats.put("extract_remaining", detailBackfillService.getRemaining());
        stats.put("auto_moved_periods", detailBackfillService.getMovedTotal());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/period-info")
    public ResponseEntity<Map<String, Object>> getPeriodInfo(@RequestParam String period) {
        int offset = syncStateRepository.findById("offset_" + period).map(SyncState::getStateValue).orElse(0);
        int total = syncStateRepository.findById("total_" + period).map(SyncState::getStateValue).orElse(0);
        return ResponseEntity.ok(Map.of(
                "period", period,
                "offset", offset,
                "total", total
        ));
    }

    @GetMapping("/interventions")
    public ResponseEntity<Page<Intervention>> getInterventions(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "ALL") String source,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        String cleanPeriod = "";
        String dbPeriod = "";
        if (period != null && !period.trim().isEmpty()) {
            cleanPeriod = period.replace("_", "-");
            dbPeriod = period.replace("-", "_");
        }

        String cleanSearch = "";
        if (search != null && !search.trim().isEmpty()) {
            cleanSearch = search.trim();
        }

        Page<Intervention> result = interventionRepository.findFilteredInterventions(
                cleanSearch, source, cleanPeriod, dbPeriod, PageRequest.of(page, size)
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
            System.err.println("❌ ERREUR CRITIQUE DANS LE CONTROLEUR EXPORT : " + t.getMessage());
            t.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
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

    @PostMapping("/rescan-period")
    public ResponseEntity<Map<String, Object>> rescanPeriod(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        if (period == null || period.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Période invalide."));
        }
        if (syncOrchestrator.isRunning()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Arrêtez d'abord la Time Machine."));
        }
        syncOrchestrator.rescanPeriod(period);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Smart Rescan lancé pour la période " + period));
    }

    @GetMapping("/events")
    public ResponseEntity<List<SyncEvent>> getEvents(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") long beforeId,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(eventLogService.search(source, level, period, beforeId, search, limit));
    }

    @GetMapping(value = "/events/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> exportEvents(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "2000") int limit) {
        String name = "gringotts-logs" + (period == null || period.isBlank() ? "" : "-" + period) + ".tsv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(eventLogService.exportText(source, level, period, search, limit));
    }

    @GetMapping("/periods")
    public ResponseEntity<List<Map<String, Object>>> getPeriods() {
        return ResponseEntity.ok(autoPilotService.periodsOverview());
    }

    @GetMapping("/healer")
    public ResponseEntity<Map<String, Object>> getHealer() {
        return ResponseEntity.ok(syncOrchestrator.healerInsights());
    }

    // ---------------------------------------------------------------- Console live
    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> activity(@RequestParam(defaultValue = "0") long after,
                                                        @RequestParam(required = false) String engine,
                                                        @RequestParam(defaultValue = "400") int limit) {
        return ResponseEntity.ok(activityFeed.since(after, Math.max(1, Math.min(limit, 2000)), engine));
    }

    // ---------------------------------------------------------------- Explorateur
    @GetMapping("/explorer")
    public ResponseEntity<Map<String, Object>> explorer(@RequestParam Map<String, String> params) {
        ExplorerService.Filters f = ExplorerService.parse(params);
        int page = parseInt(params.get("page"), 0), size = parseInt(params.get("size"), 50);
        return ResponseEntity.ok(explorerService.search(f, params.get("sort"), page, size));
    }

    @GetMapping("/explorer/facets")
    public ResponseEntity<Map<String, Object>> explorerFacets(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(explorerService.facets(ExplorerService.parse(params)));
    }

    @GetMapping("/explorer/item/{id}")
    public ResponseEntity<Map<String, Object>> explorerItem(@PathVariable long id) {
        return ResponseEntity.ok(explorerService.detail(id));
    }

    @GetMapping("/explorer/export")
    public ResponseEntity<byte[]> explorerExport(@RequestParam Map<String, String> params) {
        ExplorerService.Filters f = ExplorerService.parse(params);
        List<Long> ids = explorerService.ids(f, 200_000);
        if (ids.isEmpty()) return ResponseEntity.noContent().build();
        List<String> cats = f.dims().getOrDefault("categorie", List.of());
        String type = cats.size() == 1 && List.of("RACC", "SAV", "RZO").contains(cats.get(0)) ? cats.get(0) : "ALL";
        byte[] data = exportExcelService.generateExcelForIds(ids, type);
        String name = "Export_Gringotts_" + type + "_" + ids.size() + "_EPS.xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    @PostMapping("/scope")
    public ResponseEntity<Map<String, Object>> setScope(@RequestBody Map<String, String> body) {
        try {
            autoPilotService.setScope(body.get("mode"), body.get("start"));
            return ResponseEntity.ok(autoPilotService.describe());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify-period")
    public ResponseEntity<Map<String, Object>> verifyPeriod(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        if (period == null || period.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Période invalide."));
        return ResponseEntity.ok(autoPilotService.verifyNow(period.trim()));
    }

    @PostMapping("/recollect-period")
    public ResponseEntity<Map<String, Object>> recollectPeriod(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        if (period == null || period.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Période invalide."));
        autoPilotService.requestRecollect(period.trim());
        return ResponseEntity.ok(Map.of("ok", true, "message", "Collecte complète de " + period + " programmée."));
    }

    /** Inspecteur d'EPS : état en base + vérification en direct chez Bytel (live=true), avec réparation auto. */
    @GetMapping("/check/{idIntervention}")
    public ResponseEntity<Map<String, Object>> checkEps(@PathVariable String idIntervention,
                                                        @RequestParam(defaultValue = "false") boolean live) {
        return ResponseEntity.ok(syncOrchestrator.inspectEps(idIntervention, live));
    }

    @GetMapping("/autopilot")
    public ResponseEntity<Map<String, Object>> getAutopilot() {
        return ResponseEntity.ok(autoPilotService.describe());
    }

    @PostMapping("/autopilot")
    public ResponseEntity<Map<String, Object>> setAutopilot(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        autoPilotService.setEnabled(enabled);
        return ResponseEntity.ok(autoPilotService.describe());
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopSync() {
        // Arrêt manuel = l'humain reprend la main : on coupe l'AutoPilot sinon il relancerait tout.
        if (autoPilotService.isEnabled()) autoPilotService.setEnabled(false);
        syncOrchestrator.stopSync();
        return ResponseEntity.ok(Map.of("message", "Arrêté."));
    }

    @PostMapping("/start-healer")
    public ResponseEntity<Map<String, String>> startHealer(@RequestBody Map<String, String> body) {
        String mode = body.getOrDefault("mode", "TIME_MACHINE");
        syncOrchestrator.startHealer(mode);
        return ResponseEntity.ok(Map.of("message", "Healer démarré en mode " + mode));
    }

    @PostMapping("/stop-healer")
    public ResponseEntity<Map<String, String>> stopHealer() {
        if (autoPilotService.isEnabled()) autoPilotService.setEnabled(false);
        syncOrchestrator.stopHealer();
        return ResponseEntity.ok(Map.of("message", "Healer arrêté."));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetSync() {
        if (autoPilotService.isEnabled()) autoPilotService.setEnabled(false);
        new Thread(syncOrchestrator::purgeDatabase).start();
        return ResponseEntity.ok(Map.of("message", "Purge en cours..."));
    }

    @PostMapping("/purge-period")
    public ResponseEntity<Map<String, Object>> purgePeriod(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        if (period == null || period.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Période invalide."));
        }
        int deleted = syncOrchestrator.purgePeriod(period);
        return ResponseEntity.ok(Map.of("ok", true, "message", deleted + " interventions supprimées pour la période " + period));
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
        String currentPeriod = syncOrchestrator.getCurrentPeriod();
        if (currentPeriod != null) {
            SyncState state = syncStateRepository.findById("offset_" + currentPeriod).orElse(new SyncState("offset_" + currentPeriod, value, null));
            state.setStateValue(value);
            syncStateRepository.save(state);
            return ResponseEntity.ok(Map.of("ok", true, "message", "Offset forcé à " + value));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Aucune période en cours."));
    }

    @PostMapping("/retry-failed-heals")
    public ResponseEntity<Map<String, Object>> retryFailedHeals() {
        int count = syncOrchestrator.retryFailedHeals();
        return ResponseEntity.ok(Map.of("ok", true, "message", count + " EPS Fantômes remis en file d'attente pour le Healer."));
    }

    // ---------------------------------------------------------------- API Externe : SAV en état final EN_ATTENTE_VALIDATION_PARTENAIRE
    @GetMapping("/attente-validation")
    public ResponseEntity<Map<String, Object>> attenteValidation(
            @RequestParam(required = false) String periode,
            @RequestParam(required = false) String categorie,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(partenaireApiService.fetchAttenteValidation(periode, categorie, q, page, size));
    }

    @GetMapping("/attente-validation/export")
    public ResponseEntity<byte[]> attenteValidationExport(
            @RequestParam(required = false) String periode,
            @RequestParam(required = false) String categorie,
            @RequestParam(required = false) String q) {
        byte[] data = partenaireApiService.exportExcel(periode, categorie, q);
        String name = "SAV_Attente_Validation_Partenaire" + (periode == null || periode.isBlank() ? "" : "_" + periode) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    // 🚀 L'FIX HNA: Zidna String period f request body bash Java ydirha 3la l'mois li 3zelti nti b dbt
    @PostMapping("/fix-periods")
    public ResponseEntity<Map<String, Object>> fixPeriods(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        if (period == null || period.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Période invalide."));
        }
        int fixed = syncOrchestrator.fixPeriodsForMonth(period);
        return ResponseEntity.ok(Map.of("ok", true, "message", fixed + " EPS intrus ont été trouvés dans " + period + " et déplacés vers leur vrai mois."));
    }
}