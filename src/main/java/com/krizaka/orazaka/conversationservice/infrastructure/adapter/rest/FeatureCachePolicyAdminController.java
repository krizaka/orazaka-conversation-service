package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureCachePolicyManager;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administrator's side of the prompt-cache policy (ADR-094 §4.5, orazaka-ai-engine#173): what
 * of a feature's prompt a provider may cache ({@code NONE}, {@code SYSTEM_ONLY}, {@code
 * SYSTEM_AND_TOOLS}). A database row, never a constant; a SENSITIVE turn is never cached whatever
 * the row says. Administrators only; every write is on the operator surface and leaves an audit
 * line ({@link OperatorAuditPaths}).
 */
@RestController
@RequestMapping("/api/v1/models/admin/feature-cache-policies")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class FeatureCachePolicyAdminController {

  private static final String DEFAULT = FeatureCachePolicyManager.DEFAULT_FEATURE_KEY;

  private final FeatureCachePolicyManager policies;

  public FeatureCachePolicyAdminController(FeatureCachePolicyManager policies) {
    this.policies = Objects.requireNonNull(policies, "FeatureCachePolicyManager must not be null");
  }

  /** The body of a policy update. */
  public record Request(String strategy) {}

  /** One policy as the console shows it. */
  public record Policy(String featureKey, String strategy, boolean isDefault) {}

  /** The policies and the strategies the console offers. */
  public record Response(List<String> strategies, List<Policy> policies) {}

  /**
   * Every policy, the default row first.
   *
   * @return the policies and the allowed strategies
   */
  @GetMapping
  public Response list() {
    List<Policy> rows =
        policies.all().entrySet().stream()
            .map(e -> new Policy(e.getKey(), e.getValue(), DEFAULT.equals(e.getKey())))
            .toList();
    return new Response(FeatureCachePolicyManager.STRATEGIES.stream().sorted().toList(), rows);
  }

  /**
   * Sets the policy of a feature ({@code *} is the default row).
   *
   * @param featureKey the feature key
   * @param request the new strategy
   * @return the stored row
   * @throws InvalidRequestProblem when the strategy is missing or unknown (400)
   */
  @PutMapping("/{featureKey}")
  public Policy put(@PathVariable String featureKey, @RequestBody Request request) {
    String strategy = request == null ? null : request.strategy();
    if (strategy == null || strategy.isBlank()) {
      throw new InvalidRequestProblem("strategy is required");
    }
    try {
      policies.upsert(featureKey, strategy);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestProblem(e.getMessage());
    }
    return new Policy(featureKey, strategy, DEFAULT.equals(featureKey));
  }

  /**
   * Removes the policy a feature set for itself; the default row then applies.
   *
   * @param featureKey the feature key ({@code *} is refused)
   * @return 204 when removed, 404 when the feature had no policy of its own
   */
  @DeleteMapping("/{featureKey}")
  public ResponseEntity<Void> remove(@PathVariable String featureKey) {
    return BoundEdits.respond(() -> policies.remove(featureKey));
  }
}
