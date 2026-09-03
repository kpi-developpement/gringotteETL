package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.AckRequest;
import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhpApiClient {

    private final RestClient restClient;

    public ExportResponse export(int limit) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/sync/export").queryParam("limit", limit).build())
                .retrieve()
                .body(ExportResponse.class);
    }

    public void acknowledge(List<Long> ids) {
        restClient.post()
                .uri("/api/sync/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AckRequest(ids))
                .retrieve()
                .body(Map.class);
    }

    public ImportResponse triggerImport(int offset, int limit) {
        return restClient.post()
                .uri("/api/sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("offset", offset, "limit", limit, "fetch_details", true))
                .retrieve()
                .body(ImportResponse.class);
    }

    public void resetIonos() {
        restClient.post()
                .uri("/api/sync/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();
    }

    // 🚀 L'Appel Heal
    public Map<String, Object> healData(List<String> ids) {
        log.info("Appel POST /api/sync/heal pour {} IDs", ids.size());
        return restClient.post()
                .uri("/api/sync/heal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", ids))
                .retrieve()
                .body(Map.class);
    }
}