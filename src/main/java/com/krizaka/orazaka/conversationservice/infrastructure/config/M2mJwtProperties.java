package com.krizaka.orazaka.conversationservice.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for M2M JWT validation on the Sovereign Intent Router.
 *
 * <p>Strict claim validation:
 *
 * <ul>
 *   <li>{@code iss} = "orazaka-bff"
 *   <li>{@code sub} = "orazaka-m2m"
 *   <li>{@code aud} = "orazaka-router"
 *   <li>{@code scopes} = ["intent:route"]
 * </ul>
 *
 * @param issuer Expected JWT issuer claim.
 * @param subject Expected JWT subject claim.
 * @param audience Expected JWT audience claim.
 * @param requiredScopes Required JWT scope claims.
 * @param intentTokenSecret HMAC-SHA256 secret for signing IntentTokens.
 */
@ConfigurationProperties(prefix = "orazaka.router.m2m-jwt")
public record M2mJwtProperties(
    String issuer,
    String subject,
    String audience,
    List<String> requiredScopes,
    String intentTokenSecret) {

  public M2mJwtProperties {
    if (issuer == null || issuer.isBlank()) {
      issuer = "orazaka-bff";
    }
    if (subject == null || subject.isBlank()) {
      subject = "orazaka-m2m";
    }
    if (audience == null || audience.isBlank()) {
      audience = "orazaka-router";
    }
    if (requiredScopes == null || requiredScopes.isEmpty()) {
      requiredScopes = List.of("intent:route");
    }
    if (intentTokenSecret == null || intentTokenSecret.isBlank()) {
      intentTokenSecret = "orazaka-default-dev-secret-change-in-production";
    }
    requiredScopes = List.copyOf(requiredScopes);
  }
}
