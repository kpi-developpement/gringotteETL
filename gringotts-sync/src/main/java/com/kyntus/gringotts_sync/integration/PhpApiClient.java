package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PhpApiClient {

    private final RestClient restClient;

    // Injection automatique du RestClient configuré dans ton AppConfig
    public PhpApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ExportResponse export(int limit) {
        return restClient.get()
                .uri("/export?limit={limit}", limit)
                .retrieve()
                .body(ExportResponse.class);
    }

    public void acknowledge(List<Long> ids) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);

        restClient.post()
                .uri("/ack")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // 🚀 L'FIX HNA : Ajout du paramètre "periode" (Time Machine)
    public ImportResponse triggerImport(int offset, int limit, String periode) {
        Map<String, Object> body = new HashMap<>();
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("fetch_details", true);

        if (periode != null && !periode.isEmpty()) {
            body.put("periode", periode);
        }

        return restClient.post()
                .uri("/import")
                .body(body)
                .retrieve()
                .body(ImportResponse.class);
    }

    public void resetIonos() {
        restClient.post()
                .uri("/reset")
                .retrieve()
                .toBodilessEntity();
    }

    public Map<String, Object> healData(List<String> idInterventions) {
        String ids = String.join(",", idInterventions);

        return restClient.get()
                .uri("/heal?ids={ids}", ids)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}