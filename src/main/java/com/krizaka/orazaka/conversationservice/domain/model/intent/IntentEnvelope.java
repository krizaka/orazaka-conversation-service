package com.krizaka.orazaka.conversationservice.domain.model.intent;

import java.util.Map;

/**
 * Inbound intent envelope — the canonical input port for the Sovereign Intent Router.
 *
 * <p>Accepted via REST (POST /api/v1/intent/route), verified by M2M JWT.
 *
 * @param intentId Unique correlation identifier for this intent.
 * @param userId Authenticated user originating the intent (from BFF).
 * @param payload Raw intent payload (natural language or structured command).
 * @param metadata Optional context metadata (tier, locale, feature flags).
 */
public record IntentEnvelope(
    String intentId, String userId, String payload, Map<String, Object> metadata) {

  public IntentEnvelope {
    if (intentId == null || intentId.isBlank()) {
      throw new IllegalArgumentException("IntentEnvelope requires a non-blank intentId");
    }
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("IntentEnvelope requires a non-blank userId");
    }
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("IntentEnvelope requires a non-blank payload");
    }
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
  }
}
