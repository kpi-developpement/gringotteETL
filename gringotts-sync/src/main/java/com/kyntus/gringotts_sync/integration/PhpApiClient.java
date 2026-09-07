package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PhpApiClient {

    private final RestClient restClient;

    public PhpApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ExportResponse export(int limit) {
        log.debug("[PHP-API] GET /api/sync/export?limit={}", limit);
        return restClient.get()
                .uri("/api/sync/export?limit={limit}", limit)
                .retrieve()
                .body(ExportResponse.class);
    }

    public void acknowledge(List<Long> ids) {
        log.debug("[PHP-API] POST /api/sync/ack avec {} IDs", ids.size());

        // 🚀 L'FIX HNA : Construction s7i7a dyal l'Body (Map modifiable) bach Jackson y-formattih mzyan
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);

        restClient.post()
                .uri("/api/sync/ack")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public ImportResponse triggerImport(int offset, int limit, String periode) {
        Map<String, Object> body = new HashMap<>();
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("fetch_details", true);

        if (periode != null && !periode.isEmpty()) {
            body.put("periode", periode);
        }

        log.info("[PHP-API] POST /api/sync/import | Offset={} | Limit={} | Periode={}", offset, limit, periode != null ? periode : "Global");

        return restClient.post()
                .uri("/api/sync/import")
                .body(body)
                .retrieve()
                .body(ImportResponse.class);
    }

    public void resetIonos() {
        log.warn("[PHP-API] POST /api/sync/reset | Purge totale demandée");
        restClient.post()
                .uri("/api/sync/reset")
                .retrieve()
                .toBodilessEntity();
    }

    public Map<String, Object> healData(List<String> idInterventions) {
        String ids = String.join(",", idInterventions);
        log.debug("[PHP-API] GET /api/sync/heal pour {} EPS", idInterventions.size());
        return restClient.get()
                .uri("/api/sync/heal?ids={ids}", ids)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}