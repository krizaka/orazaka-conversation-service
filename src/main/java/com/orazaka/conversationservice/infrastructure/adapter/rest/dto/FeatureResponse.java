package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import java.util.Objects;

/**
 * What this deployment can currently run: one entry per enabled capability, with its live state.
 *
 * <p><b>This record carried five more fields and it 500ed because of them</b> (ADR-069 §5). It
 * required {@code uriPath}, {@code httpMethod} and {@code payloadTemplate} to be non-null and read
 * {@code label} and {@code icon} beside them — the registry's UI-manifest half, which the chat
 * composer used to build its button row from. A capability a pack contributes has no HTTP surface,
 * so its {@code uri_path} is NULL, and {@code GET /api/v1/features} therefore answered <b>500
 * NullPointerException: uriPath must not be null</b> for any deployment with a Tier-C pack
 * installed. M3's bootstrap made that every deployment; the e2e gate M4 was asked to run first is
 * what found it.
 *
 * <p>What is left is the question the endpoint still answers and nothing else does: which
 * capabilities exist, and which of them can run right now. The composer's row comes from Studios
 * (ADR-068 §3), and a display name comes from the pack's own i18n.
 *
 * @param id the capability's key — its identity, which is what a caller needs to correlate state
 * @param available whether its engine is reachable and its node is Active
 * @param lockedReason why it is not, or {@code null} when it is available
 */
public record FeatureResponse(String id, boolean available, String lockedReason) {

  /** Compact canonical constructor enforcing the response's one invariant (ERR-106). */
  public FeatureResponse {
    Objects.requireNonNull(id, "id must not be null");
  }
}
