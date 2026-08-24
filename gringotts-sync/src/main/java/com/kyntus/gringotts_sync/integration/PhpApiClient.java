package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.AckRequest;
import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportRequest;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        log.info("Appel GET /api/sync/export?limit={}", limit);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/sync/export").queryParam("limit", limit).build())
                .retrieve()
                .body(ExportResponse.class);
    }

    public void acknowledge(List<Long> ids) {
        log.info("Appel POST /api/sync/ack pour {} IDs", ids.size());
        restClient.post()
                .uri("/api/sync/ack")
                .body(new AckRequest(ids))
                .retrieve()
                .body(Map.class); // On ignore la réponse, on veut juste que ça passe (200 OK)
    }

    public ImportResponse triggerImport(int offset) {
        log.info("Appel POST /api/sync/import avec offset={}", offset);
        return restClient.post()
                .uri("/api/sync/import")
                .body(new ImportRequest(offset))
                .retrieve()
                .body(ImportResponse.class);
    }
}