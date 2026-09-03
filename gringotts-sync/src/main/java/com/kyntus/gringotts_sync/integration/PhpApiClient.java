package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.AckRequest;
import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import com.kyntus.gringotts_sync.dto.HealRequest; // 🚀 L'Import jdid
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    // 🚀 L'FIX HNA : On utilise le DTO strict et on filtre les nulls
    public Map<String, Object> healData(List<String> ids) {
        // On s'assure qu'aucun ID n'est null ou vide avant d'envoyer à PHP
        List<String> cleanIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.trim().isEmpty())
                .toList();

        log.info("Appel POST /api/sync/heal pour {} IDs valides", cleanIds.size());

        return restClient.post()
                .uri("/api/sync/heal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HealRequest(cleanIds)) // 🚀 Utilisation du DTO
                .retrieve()
                .body(Map.class);
    }
}