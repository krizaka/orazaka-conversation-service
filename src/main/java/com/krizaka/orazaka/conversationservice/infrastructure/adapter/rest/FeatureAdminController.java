package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureRouteManager;
import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureToolBoundManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The administrator's side of the per-feature engine settings (ADR-029, orazaka-ai-engine#169): the
 * most tool rounds a feature key may run in a turn (read, replace, remove) and the models each
 * feature routes to (read only). Both are database rows, never a constant. Administrators only;
 * every write is on the operator surface and leaves an audit line ({@link OperatorAuditPaths}).
 */
@RestController
@RequestMapping("/api/v1/models/admin")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class FeatureAdminController {

  private final FeatureToolBoundManager bounds;
  private final FeatureRouteManager routes;

  public FeatureAdminController(FeatureToolBoundManager bounds, FeatureRouteManager routes) {
    this.bounds = Objects.requireNonNull(bounds, "FeatureToolBoundManager must not be null");
    this.routes = Objects.requireNonNull(routes, "FeatureRouteManager must not be null");
  }

  /** The body of a bound update. */
  public record ToolBoundRequest(Integer maxToolRounds) {}

  /** One bound as the console shows it. */
  public record ToolBound(String featureKey, int maxToolRounds, boolean isDefault) {}

  /** The bounds and the limits the console validates against. */
  public record ToolBoundsResponse(int min, int max, List<ToolBound> bounds) {}

  /** One feature's route, models best first. */
  public record FeatureRoute(String featureKey, List<String> models) {}

  /**
   * Every bound, the default row first.
   *
   * @return the bounds and the allowed range
   */
  @GetMapping("/feature-tool-bounds")
  public ToolBoundsResponse toolBounds() {
    List<ToolBound> rows =
        bounds.all().entrySet().stream()
            .map(
                e ->
                    new ToolBound(
                        e.getKey(),
                        e.getValue(),
                        FeatureToolBoundManager.DEFAULT_FEATURE_KEY.equals(e.getKey())))
            .toList();
    return new ToolBoundsResponse(
        FeatureToolBoundManager.MIN_ROUNDS, FeatureToolBoundManager.MAX_ROUNDS, rows);
  }

  /**
   * Sets the bound of a feature ({@code *} is the default row).
   *
   * @param featureKey the feature key
   * @param request the new bound, 1 to 32
   * @return the stored row
   */
  @PutMapping("/feature-tool-bounds/{featureKey}")
  public ToolBound putToolBound(
      @PathVariable String featureKey, @RequestBody ToolBoundRequest request) {
    int stored =
        BoundEdits.write(
            request == null ? null : request.maxToolRounds(),
            "maxToolRounds",
            v -> bounds.upsert(featureKey, v));
    return new ToolBound(
        featureKey, stored, FeatureToolBoundManager.DEFAULT_FEATURE_KEY.equals(featureKey));
  }

  /**
   * Removes a feature's own bound so it falls back to the default row.
   *
   * @param featureKey the feature key, never {@code *}
   * @return 204, or 404 when the feature had no row of its own
   */
  @DeleteMapping("/feature-tool-bounds/{featureKey}")
  public ResponseEntity<Void> deleteToolBound(@PathVariable String featureKey) {
    return BoundEdits.respond(() -> bounds.delete(featureKey));
  }

  /**
   * The feature routes, read only.
   *
   * @return each feature key with its models, best first
   */
  @GetMapping("/feature-routes")
  public List<FeatureRoute> featureRoutes() {
    Map<String, List<String>> all = routes.allRoutes();
    return all.entrySet().stream().map(e -> new FeatureRoute(e.getKey(), e.getValue())).toList();
  }
}
