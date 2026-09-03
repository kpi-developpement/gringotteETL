package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.AckRequest;
import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Map<String, Object> body = new HashMap<>();
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("fetch_details", true);

        return restClient.post()
                .uri("/api/sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ImportResponse.class);
    }

    public void resetIonos() {
        log.info("Appel POST /api/sync/reset pour vider IONOS");
        restClient.post()
                .uri("/api/sync/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();
    }

    // 🚀 L'FIX HNA : On utilise une Map native de Java au lieu du DTO pour garantir le JSON {"ids": [...]}
    public Map<String, Object> healData(List<String> ids) {
        List<String> cleanIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.trim().isEmpty())
                .toList();

        log.info("Appel POST /api/sync/heal pour {} IDs valides", cleanIds.size());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("ids", cleanIds);

        return restClient.post()
                .uri("/api/sync/heal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody) // 🚀 Map native = JSON 100% valide garanti
                .retrieve()
                .body(Map.class);
    }
}