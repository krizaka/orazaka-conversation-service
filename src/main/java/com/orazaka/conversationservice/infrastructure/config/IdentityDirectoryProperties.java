package com.orazaka.conversationservice.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Wiring of the identity-service internal API consumed by the router's user directory ({@code
 * orazaka.router.identity-directory}).
 *
 * @param baseUrl the identity service base URL
 * @param cacheTtl how long user/profile/tier lookups are cached (staleness is already bounded by
 *     the session-JWT TTL)
 */
@ConfigurationProperties(prefix = "orazaka.router.identity-directory")
public record IdentityDirectoryProperties(
    String baseUrl, @DefaultValue("PT60S") Duration cacheTtl) {

  public IdentityDirectoryProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("identity-directory base-url is required");
    }
    if (cacheTtl == null || cacheTtl.isNegative()) {
      throw new IllegalArgumentException("identity-directory cache-ttl must be positive");
    }
  }
}
