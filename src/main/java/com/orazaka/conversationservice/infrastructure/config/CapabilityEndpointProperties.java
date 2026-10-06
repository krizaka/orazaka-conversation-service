package com.orazaka.conversationservice.infrastructure.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The synchronous endpoints this service serves, and the capability each one runs ({@code
 * orazaka.router.capability-endpoints}).
 *
 * <p><b>Why this exists at all.</b> {@code OperationGraphFilter} refuses a request for a capability
 * whose engine is offline, and it used to find the endpoint to match by reading {@code uri_path}
 * off the capability row. Those columns are gone (ADR-069 §5): the endpoints they named were door
 * 1's and were deleted with it, so every value in that column named something that no longer
 * existed — the filter's matching had quietly become dead except for one row.
 *
 * <p>That one row is the point. {@code orazaka.core.chat.completion} <b>is</b> synchronous and
 * stays off the broker (AGENTS.md §6), so this service really does serve it over HTTP, and an
 * availability gate in front of it is a live control rather than a formality. The address of an
 * endpoint belongs to the service that serves it, which is here — not in a registry shared by every
 * context.
 *
 * <p>A map rather than a constant, and read rather than branched on: a second synchronous
 * capability would be a line of yaml, and no code would learn a capability key.
 *
 * @param paths path prefix → the capability key whose state gates it
 */
@ConfigurationProperties(prefix = "orazaka.router.capability-endpoints")
public record CapabilityEndpointProperties(@DefaultValue Map<String, String> paths) {

  /** Compact canonical constructor enforcing the wiring's invariants (ERR-106). */
  public CapabilityEndpointProperties {
    paths = paths == null ? Map.of() : Map.copyOf(paths);
  }
}
