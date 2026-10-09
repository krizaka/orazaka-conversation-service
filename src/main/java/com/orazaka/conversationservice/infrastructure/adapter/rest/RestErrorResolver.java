package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.users.domain.exception.InvalidRequestException;
import com.orazaka.billing.domain.exception.InsufficientCreditsException;
import com.orazaka.core.application.pipeline.PipelineDisabledException;
import com.orazaka.core.application.pipeline.PipelineShortCircuitException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST exception advice for the router's own endpoints. Identity-flow exceptions are handled by the
 * identity service's advice; the router only maps the request-validation failures its media and job
 * endpoints raise (via the identity-api contract exception).
 */
@RestControllerAdvice(basePackages = "com.orazaka.conversationservice.infrastructure.adapter.rest")
public class RestErrorResolver {

  private static final Logger logger = LoggerFactory.getLogger(RestErrorResolver.class);
  private static final String ERROR_KEY = "error";

  /** Maps an invalid request (unknown model, malformed payload semantics) to 400 Bad Request. */
  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<Map<String, String>> handleInvalidRequest(InvalidRequestException ex) {
    logger.warn("REST {} intercepted: {}", ex.getClass().getSimpleName(), ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(ERROR_KEY, ex.getMessage()));
  }

  /**
   * Relays a credit refusal taken at submission as the structured 402 of the billing design (§6.1).
   *
   * <p>Every field is carried from the ledger's own refusal rather than re-derived: the paywall
   * needs the balance, the shortfall, the capability and the remedies, and a second opinion formed
   * here would drift from the numbers that actually blocked the request.
   *
   * @param ex the refusal raised while reserving the job's cost
   * @return {@code 402} with the refusal the ledger computed
   */
  @ExceptionHandler(InsufficientCreditsException.class)
  public ResponseEntity<Map<String, Object>> handleInsufficientCredits(
      InsufficientCreditsException ex) {
    logger.info(
        "Submission refused for capability {}: required {}, available {}",
        ex.capability(),
        ex.required(),
        ex.available());
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(refusalBody(ex));
  }

  /**
   * Maps an engine turn refused by the master orchestration switch to {@code 503} (ADR-062).
   *
   * <p>The operator switched the engine off: not the caller's plan and not their balance, so
   * neither {@code 402} nor {@code 403} — and never {@code upgrade_plan}, which would sell a remedy
   * for a decision that was ours.
   *
   * @param ex the refusal
   * @return {@code 503} naming the switch
   */
  @ExceptionHandler(PipelineDisabledException.class)
  public ResponseEntity<Map<String, Object>> handlePipelineDisabled(PipelineDisabledException ex) {
    logger.warn("Engine turn refused: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("status", "engine_disabled", "message", ex.getMessage()));
  }

  /**
   * Maps a gate's refusal of a chat turn (ADR-033 §6.2) onto its status.
   *
   * <p>{@code 402} and {@code 403} are different products, not different wordings of one: a plan
   * that excludes a capability is an upgrade, an empty balance is a top-up, and a UI given one
   * status for both can only offer the wrong remedy half the time. The refusal names which it is,
   * so the mapping reads the reason rather than guessing from the cause.
   *
   * @param ex the interceptor's refusal
   * @return {@code 402} with the ledger's structured refusal, or {@code 403} naming the plan gap
   */
  @ExceptionHandler(PipelineShortCircuitException.class)
  public ResponseEntity<Map<String, Object>> handlePipelineRefusal(
      PipelineShortCircuitException ex) {
    logger.info("Pipeline refused by {}: {}", ex.interceptorId(), ex.reason());
    if (ex.getCause() instanceof InsufficientCreditsException credits) {
      return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(refusalBody(credits));
    }
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            Map.of(
                "status", ex.reason(),
                "message", ex.getMessage() == null ? ex.reason() : ex.getMessage(),
                "remedies", List.of("upgrade_plan")));
  }

  private static Map<String, Object> refusalBody(InsufficientCreditsException ex) {
    return Map.of(
        "status", "insufficient_credits",
        "capability", ex.capability().name(),
        "required", ex.required(),
        "balance", ex.available(),
        "remedies", ex.remedies());
  }
}
