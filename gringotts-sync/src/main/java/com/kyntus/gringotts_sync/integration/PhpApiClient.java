package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
        String jsonIds = ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        String jsonBody = "{\"ids\":[" + jsonIds + "]}";

        restClient.post()
                .uri("/api/sync/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .toBodilessEntity();
    }

    public ImportResponse triggerImport(int offset, int limit) {
        // 🚀 L'FIX HNA : fetch_details = true bach njibo détails f de99a we7da !
        String jsonBody = String.format("{\"offset\":%d,\"limit\":%d,\"fetch_details\":true}", offset, limit);

        return restClient.post()
                .uri("/api/sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
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

    public Map<String, Object> healData(List<String> ids) {
        List<String> cleanIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.trim().isEmpty())
                .toList();

        String idsString = String.join(",", cleanIds);
        log.info("Appel GET /api/sync/heal pour {} IDs valides", cleanIds.size());

        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/sync/heal").queryParam("ids", idsString).build())
                .retrieve()
                .body(Map.class);
    }
}