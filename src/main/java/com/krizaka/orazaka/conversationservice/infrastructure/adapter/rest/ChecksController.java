package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.domain.port.ProductChecks;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.CheckResponse;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.SubmitCheckRequest;
import com.krizaka.orazaka.core.domain.model.checks.CheckCall;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckSubject;
import com.krizaka.orazaka.core.domain.model.security.Actor;
import com.krizaka.users.domain.model.User;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The door for products to run external checks through Orazaka (orazaka-ai-engine#105, ADR-090):
 * court records and credit, submitted and, when the provider answers later, collected. The caller
 * is the verified actor of the session (ADR-091), never a field of the body; the tenant is the
 * actor's. The answer is the engine's closed {@code CheckResult}: an outcome and a category, no
 * record. Identity checks are not offered here.
 */
@RestController
@RequestMapping("/api/v1/checks")
public class ChecksController {

  /**
   * The access audit of ADR-091 (marker {@code ACCESS_AUDIT}): who was refused, never what was
   * asked.
   */
  private static final Logger ACCESS_AUDIT = LoggerFactory.getLogger(ChecksController.class);

  private static final Marker ACCESS = MarkerFactory.getMarker("ACCESS_AUDIT");

  private static final Map<String, CheckPurpose> PURPOSES =
      Map.of("court-records", CheckPurpose.COURT_RECORDS, "credit", CheckPurpose.CREDIT);

  private final ProductChecks checks;

  /**
   * @param checks the checks service
   */
  public ChecksController(ProductChecks checks) {
    this.checks = Objects.requireNonNull(checks, "ProductChecks must not be null");
  }

  /**
   * Starts a check.
   *
   * @param purpose {@code court-records} or {@code credit}
   * @param request the subject and the consent statement
   * @param user the authenticated caller
   * @return the closed result; 401 without a verified actor, 403 without the role, 429 over the
   *     rate limit, 400 for a malformed subject
   */
  @PostMapping("/{purpose}")
  public ResponseEntity<@NotNull Object> submit(
      @PathVariable String purpose,
      @RequestBody SubmitCheckRequest request,
      @AuthenticationPrincipal User user) {
    Actor actor = actorOf(user);
    ResponseEntity<Object> refused = gate(actor);
    if (refused != null) {
      return refused;
    }
    CheckPurpose asked = PURPOSES.get(purpose);
    if (asked == null) {
      return ResponseEntity.notFound().build();
    }
    CheckCall call;
    try {
      call =
          new CheckCall(
              actor.subject(),
              request.runId(),
              new CheckSubject(
                  request.subjectId(),
                  request.lastName(),
                  request.firstName(),
                  request.birthDate(),
                  request.address()),
              request.region());
    } catch (NullPointerException | IllegalArgumentException invalid) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid subject"));
    }
    return ResponseEntity.ok(
        CheckResponse.of(
            asked, checks.submit(actor, asked, call, request.consented()), call.runId()));
  }

  /**
   * Collects a check that came back PENDING.
   *
   * @param providerRef the reference the submission answered
   * @param subjectId the opaque id of the subject
   * @param consented the caller's statement that the consent still stands
   * @param user the authenticated caller
   * @return the answer; 404 for a reference the caller's tenant never received
   */
  @GetMapping("/{providerRef}")
  public ResponseEntity<@NotNull Object> collect(
      @PathVariable String providerRef,
      @RequestParam String subjectId,
      @RequestParam(defaultValue = "false") boolean consented,
      @AuthenticationPrincipal User user) {
    Actor actor = actorOf(user);
    ResponseEntity<Object> refused = gate(actor);
    if (refused != null) {
      return refused;
    }
    Optional<ProductChecks.Collected> collected =
        checks.collect(actor, providerRef, subjectId, consented);
    return collected
        .<ResponseEntity<Object>>map(
            answer -> ResponseEntity.ok(CheckResponse.of(answer.purpose(), answer.result(), null)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** The refusal before anything is asked, or {@code null} when the caller may go on. */
  private ResponseEntity<Object> gate(Actor actor) {
    if (actor == null) {
      return ResponseEntity.status(401).build();
    }
    if (!checks.permits(actor)) {
      ACCESS_AUDIT.warn(
          ACCESS,
          "checks refused tenant={} subject={} reason=MISSING_ROLE",
          actor.tenant(),
          actor.subject());
      return ResponseEntity.status(403).build();
    }
    long wait = checks.throttle(actor);
    if (wait > 0) {
      ACCESS_AUDIT.warn(
          ACCESS,
          "checks refused tenant={} subject={} reason=RATE_LIMIT",
          actor.tenant(),
          actor.subject());
      return ResponseEntity.status(429).header("Retry-After", Long.toString(wait)).build();
    }
    return null;
  }

  /** The actor of the session: none for a caller that is not a signed-in, unambiguous user. */
  private static Actor actorOf(User user) {
    return user == null ? null : Actor.of(user.id().toString(), user.authorities()).orElse(null);
  }
}
