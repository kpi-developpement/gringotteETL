package com.kyntus.gringotts_sync.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*") // En prod, mets l'IP de ton Next.js
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}