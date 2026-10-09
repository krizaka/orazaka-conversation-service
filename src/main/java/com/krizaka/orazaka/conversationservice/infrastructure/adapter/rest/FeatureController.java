package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.application.service.CapabilityConfigService;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.FeatureResponse;
import com.krizaka.orazaka.core.application.engine.GraphEngine;
import com.krizaka.orazaka.core.domain.model.NodeState;
import com.krizaka.orazaka.core.domain.model.OperationNode;
import com.krizaka.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the {@code features} (capability) resource.
 *
 * <p>Consolidates the former Bootstrap (client feature feed) and AdminFeature (admin capability
 * registry) controllers. The client feed lists enabled capabilities with live degraded-mode state;
 * admin endpoints (list-all, toggle) are guarded with method-level {@code @PreAuthorize}. All DB
 * access goes through the persistence {@link CapabilityManager} port — no JPA entity is exposed.
 */
@RestController
@RequestMapping("/api/v1/features")
public class FeatureController {

  private final CapabilityManager capabilityManager;
  private final CapabilityConfigService capabilityConfigService;
  private final GraphEngine graphEngine;

  public FeatureController(
      CapabilityManager capabilityManager,
      GraphEngine graphEngine,
      CapabilityConfigService capabilityConfigService) {
    this.capabilityConfigService =
        Objects.requireNonNull(capabilityConfigService, "CapabilityConfigService must not be null");
    this.capabilityManager =
        Objects.requireNonNull(capabilityManager, "CapabilityManager must not be null");
    this.graphEngine = Objects.requireNonNull(graphEngine, "GraphEngine must not be null");
  }

  /** DTO payload for capability toggle updates — prevents mass assignment of capability fields. */
  record CapabilityPayload(Boolean isEnabled) {}

  /**
   * What this deployment can currently run: every enabled capability with its live degraded-mode
   * state.
   *
   * <p>It was "the client feed" — the chat composer read it to build its button row, which is why
   * it carried a label, an icon, a path, a verb and a payload template. The composer is served from
   * Studios since ADR-068 §3 and those five columns are gone (ADR-069 §5); what remains is the
   * availability question, which nothing else answers.
   */
  @GetMapping
  public ResponseEntity<List<FeatureResponse>> getEnabledFeatures() {
    Map<String, NodeState> states =
        graphEngine.compileGraph().nodes().stream()
            .collect(Collectors.toMap(OperationNode::id, OperationNode::state, (a, b) -> a));
    List<FeatureResponse> enabledFeatures =
        capabilityManager.findAll().stream()
            .filter(CapabilityDeclaration::enabled)
            .map(c -> toResponse(c, states.get(c.featureKey())))
            .toList();
    return ResponseEntity.ok(enabledFeatures);
  }

  /** Admin: all capabilities (enabled or not) from the database. */
  @GetMapping("/all")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<List<CapabilityDeclaration>> getAllFeatures() {
    return ResponseEntity.ok(capabilityManager.findAll());
  }

  /**
   * Admin: toggles an existing capability's enabled state.
   *
   * <p>Capabilities are seeded rows (handler and routing are NOT NULL), so this endpoint only flips
   * the enabled flag of a known capability — it never creates one. Unknown keys yield 404. What
   * {@code is_enabled} now means is written on the column itself (ADR-069 §5): the platform does
   * not dispatch a disabled capability, and that is the whole of it — it stopped being "a button
   * appears" when the composer stopped reading this table.
   */
  @PutMapping("/{featureKey}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<CapabilityDeclaration> updateFeature(
      @PathVariable String featureKey, @RequestBody CapabilityPayload payload) {
    return capabilityManager
        .findByFeatureKey(featureKey)
        .map(
            dto ->
                ResponseEntity.ok(
                    capabilityConfigService.save(
                        new CapabilityDeclaration(
                            dto.featureKey(),
                            dto.handlerKey(),
                            dto.routingKey(),
                            dto.billableUnit(),
                            dto.billableCapability(),
                            "BATCH",
                            dto.inputSchema(),
                            dto.outputSchema(),
                            Boolean.TRUE.equals(payload.isEnabled())))))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static FeatureResponse toResponse(CapabilityDeclaration capability, NodeState state) {
    boolean available =
        !(state instanceof NodeState.Locked || state instanceof NodeState.Invisible);
    String lockedReason = (state instanceof NodeState.Locked locked) ? locked.reason() : null;
    return new FeatureResponse(capability.featureKey(), available, lockedReason);
  }
}
