package com.orazaka.conversationservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session-JWT validation wiring ({@code orazaka.identity.jwt}) — the router's contract copy of the
 * shared HS256 secret the identity service signs with (every host validates locally; no per-request
 * identity hop).
 *
 * @param secret the HMAC-SHA256 signing secret (≥ 32 bytes)
 */
@ConfigurationProperties(prefix = "orazaka.identity.jwt")
public record SessionJwtProperties(String secret) {

  public SessionJwtProperties {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException(
          "orazaka.identity.jwt.secret must be at least 32 characters (256-bit HS256)");
    }
  }
}
