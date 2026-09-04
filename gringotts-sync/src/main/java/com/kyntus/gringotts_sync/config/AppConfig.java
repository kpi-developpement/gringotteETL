package com.kyntus.gringotts_sync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class AppConfig {

    @Value("${kyntus.php-api.base-url}")
    private String baseUrl;

    @Value("${kyntus.php-api.sync-key}")
    private String syncKey;

    @Bean
    public RestClient restClient() {
        // 🚀 L'FIX HNA : N-kelliw Java ytsenna PHP bima jab l'details kamlin
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000); // 15 secondes bach y-connecta l Ionos
        factory.setReadTimeout(180000);   // 3 minutes (180s) bach ytsenna l'execution dyal 400 details f PHP

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-SYNC-KEY", syncKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}