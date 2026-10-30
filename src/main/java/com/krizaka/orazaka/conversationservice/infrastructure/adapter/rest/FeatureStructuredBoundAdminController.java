package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureStructuredBoundManager;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The administrator's side of the structured-output bound (ADR-094 §4.6, orazaka-ai-engine#172):
 * the most answers a feature key's call may ask a model for (read, replace, remove). A database
 * row, never a constant. Administrators only; every write is on the operator surface and leaves an
 * audit line ({@link OperatorAuditPaths}).
 */
@RestController
@RequestMapping("/api/v1/models/admin/feature-structured-bounds")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class FeatureStructuredBoundAdminController {

  private static final String DEFAULT = FeatureStructuredBoundManager.DEFAULT_FEATURE_KEY;

  private final FeatureStructuredBoundManager bounds;

  public FeatureStructuredBoundAdminController(FeatureStructuredBoundManager bounds) {
    this.bounds = Objects.requireNonNull(bounds, "FeatureStructuredBoundManager must not be null");
  }

  /** The body of a bound update. */
  public record Request(Integer maxAttempts) {}

  /** One bound as the console shows it. */
  public record Bound(String featureKey, int maxAttempts, boolean isDefault) {}

  /** The bounds and the limits the console validates against. */
  public record Response(int min, int max, List<Bound> bounds) {}

  /**
   * Every bound, the default row first.
   *
   * @return the bounds and the allowed range
   */
  @GetMapping
  public Response list() {
    List<Bound> rows =
        bounds.all().entrySet().stream()
            .map(e -> new Bound(e.getKey(), e.getValue(), DEFAULT.equals(e.getKey())))
            .toList();
    return new Response(
        FeatureStructuredBoundManager.MIN_ATTEMPTS,
        FeatureStructuredBoundManager.MAX_ATTEMPTS,
        rows);
  }

  /**
   * Sets the bound of a feature ({@code *} is the default row).
   *
   * @param featureKey the feature key
   * @param request the new bound, 1 to 5
   * @return the stored row
   */
  @PutMapping("/{featureKey}")
  public Bound put(@PathVariable String featureKey, @RequestBody Request request) {
    int stored =
        BoundEdits.write(
            request == null ? null : request.maxAttempts(),
            "maxAttempts",
            v -> bounds.upsert(featureKey, v));
    return new Bound(featureKey, stored, DEFAULT.equals(featureKey));
  }

  /**
   * Removes the structured-output bound a feature set for itself; the default row then applies.
   *
   * @param featureKey the feature key ({@code *} is refused)
   * @return 204 when removed, 404 when the feature had no bound of its own
   */
  @DeleteMapping("/{featureKey}")
  public ResponseEntity<Void> remove(@PathVariable String featureKey) {
    return BoundEdits.respond(() -> bounds.delete(featureKey));
  }
}
