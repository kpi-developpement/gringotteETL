package com.kyntus.gringotts_sync.integration;

import com.kyntus.gringotts_sync.dto.ExportResponse;
import com.kyntus.gringotts_sync.dto.ImportResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PhpApiClient {

    private final RestTemplate restTemplate;

    @Value("${kyntus.php.api.url:http://host.docker.internal:8080/api/sync}")
    private String phpApiUrl;

    @Value("${kyntus.php.api.key:KYNTUS_SUPER_SECRET_SYNC_KEY_2026_!@#}")
    private String syncApiKey;

    public PhpApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-SYNC-KEY", syncApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public ExportResponse export(int limit) {
        String url = phpApiUrl + "/export?limit=" + limit;
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
        return restTemplate.exchange(url, HttpMethod.GET, entity, ExportResponse.class).getBody();
    }

    public void acknowledge(List<Long> ids) {
        String url = phpApiUrl + "/ack";
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, createHeaders());
        restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
    }

    // 🚀 La Time Machine est passée ici de manière sécurisée
    public ImportResponse triggerImport(int offset, int limit, String periode) {
        String url = phpApiUrl + "/import";
        Map<String, Object> body = new HashMap<>();
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("fetch_details", true);

        if (periode != null && !periode.isEmpty()) {
            body.put("periode", periode);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, createHeaders());
        return restTemplate.exchange(url, HttpMethod.POST, entity, ImportResponse.class).getBody();
    }

    public void resetIonos() {
        String url = phpApiUrl + "/reset";
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
        restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
    }

    public Map<String, Object> healData(List<String> idInterventions) {
        String ids = String.join(",", idInterventions);
        String url = phpApiUrl + "/heal?ids=" + ids;
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
        return restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
    }
}