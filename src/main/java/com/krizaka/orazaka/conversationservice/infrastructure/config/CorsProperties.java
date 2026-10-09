package com.krizaka.orazaka.conversationservice.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties record for the REST API CORS policy.
 *
 * <p>Maps properties under the {@code orazaka.cors} prefix in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "orazaka.cors")
public record CorsProperties(
    List<String> allowedOrigins,
    List<String> allowedMethods,
    List<String> allowedHeaders,
    Boolean allowCredentials) {}
