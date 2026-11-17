package com.kyntus.gringotts_sync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;
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
        // HttpClient JDK : pool de connexions keep-alive réutilisées (plus de handshake TLS à chaque appel).
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMinutes(3)); // 3 minutes d'attente max

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-SYNC-KEY", syncKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}