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
        // 🚀 L'FIX HNA : N-tal3o l'Timeout bach Java y-sber 3la PHP w Bouygues
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000); // 30 secondes bach y-connecta
        factory.setReadTimeout(300000);   // 5 minutes bach ytsenna l'import yssali

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-SYNC-KEY", syncKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}