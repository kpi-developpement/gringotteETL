package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.AckRequest;
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
        return restClient.get()
                .uri("/api/sync/export?limit={limit}", limit)
                .retrieve()
                .body(ExportResponse.class);
    }

    public void acknowledge(List<Long> ids) {
        // 🛡️ L'FIX HNA : On utilise le DTO officiel AckRequest pour garantir un JSON parfait {"ids": [1,2,3]}
        restClient.post()
                .uri("/api/sync/ack")
                .body(new AckRequest(ids))
                .retrieve()
                .toBodilessEntity();
    }

    public ImportResponse triggerImport(int offset, int limit, String periode) {
        Map<String, Object> body = new HashMap<>();
        body.put("offset", offset);
        body.put("limit", limit);

        body.put("fetch_details", false);

        if (periode != null && !periode.isEmpty()) {
            body.put("periode", periode);
        }

        log.info("[PHP-API] POST /api/sync/import | Offset={} | Limit={} | Période={}", offset, limit, periode != null ? periode : "Global");

        return restClient.post()
                .uri("/api/sync/import")
                .body(body)
                .retrieve()
                .body(ImportResponse.class);
    }

    public void resetIonos() {
        restClient.post()
                .uri("/api/sync/reset")
                .retrieve()
                .toBodilessEntity();
    }

    public Map<String, Object> healData(List<String> idInterventions) {
        String ids = String.join(",", idInterventions);
        return restClient.get()
                .uri("/api/sync/heal?ids={ids}", ids)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}