package com.orazaka.conversationservice.domain.model.intent;

import java.time.Instant;

/**
 * Signed intent token — the output of Level 0 gate evaluation.
 *
 * <p>Contains the gate decision, issuance/expiry timestamps, and an HMAC-SHA256 signature.
 * Downstream pipeline stages verify this token before processing.
 *
 * @param tokenValue HMAC-SHA256 signed token string.
 * @param issuedAt Token issuance timestamp.
 * @param expiresAt Token expiration timestamp.
 * @param gateDecision The gate verdict (e.g., "AUTH_PASS", "SECOPS_CLEARED", "COST_SHIELD_CLOUD").
 */
public record IntentToken(
    String tokenValue, Instant issuedAt, Instant expiresAt, String gateDecision) {

  public IntentToken {
    if (tokenValue == null || tokenValue.isBlank()) {
      throw new IllegalArgumentException("IntentToken requires a non-blank tokenValue");
    }
    if (issuedAt == null) {
      throw new IllegalArgumentException("IntentToken requires a non-null issuedAt");
    }
    if (expiresAt == null) {
      throw new IllegalArgumentException("IntentToken requires a non-null expiresAt");
    }
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("expiresAt must be after issuedAt");
    }
    if (gateDecision == null || gateDecision.isBlank()) {
      throw new IllegalArgumentException("IntentToken requires a non-blank gateDecision");
    }
  }
}
