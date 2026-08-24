package com.kyntus.gringotts_sync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling // Active le système de Cron jobs
public class AppConfig {

    @Value("${kyntus.php-api.base-url}")
    private String baseUrl;

    @Value("${kyntus.php-api.sync-key}")
    private String syncKey;

    @Bean
    public RestClient phpApiClient() {
        // On configure le client HTTP m3a l'URL w l'API Key par défaut
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-SYNC-KEY", syncKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}