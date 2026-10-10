package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.orazaka.core.application.pipeline.PipelineDisabledException;
import com.krizaka.orazaka.core.application.pipeline.PipelineShortCircuitException;
import com.krizaka.users.domain.exception.InvalidRequestException;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The conversation service's own refusals, on top of krizaka-web's Problem Details.
 *
 * <p>Every other failure is answered by krizaka-web's {@link ProblemDetailsAdvice} (RFC 9457 with
 * {@code code} and {@code requestId}). Two kinds stay here:
 *
 * <ul>
 *   <li>{@link InvalidRequestException} — raised by the users contract, which knows nothing of
 *       krizaka-web — is translated into a {@code 400} {@code invalid-request} problem by the kit's
 *       own advice, so it reads like every other error;
 *   <li>the engine's refusals ({@code 402}, {@code 403}, {@code 503}) keep the structured refusal
 *       body the paywall reads ({@code status}, {@code capability}, {@code required}, {@code
 *       balance}, {@code remedies} — {@code RefusalSchema} in orazaka-shared). Its {@code status}
 *       is a reason string, not RFC 9457's number: moving it to Problem Details is a client change
 *       of its own (ADR-074).
 * </ul>
 *
 * <p>Ordered first: the kit's advice also answers {@code Exception}, and two advices claiming the
 * same exception are consulted by order.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    basePackages = "com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest")
public class RestErrorResolver {

  private static final Logger logger = LoggerFactory.getLogger(RestErrorResolver.class);

  private final ProblemDetailsAdvice problems;

  /**
   * Creates the advice.
   *
   * @param problems krizaka-web's advice, which formats the {@code invalid-request} problem
   */
  public RestErrorResolver(ProblemDetailsAdvice problems) {
    this.problems = problems;
  }

  /**
   * Maps an invalid request (unknown model, malformed payload semantics) to a {@code 400} Problem
   * Details, code {@code invalid-request}.
   *
   * @param ex the users contract's exception
   * @return the problem, with {@code code} and {@code requestId}
   */
  @ExceptionHandler(InvalidRequestException.class)
  public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
    logger.warn("REST {} intercepted: {}", ex.getClass().getSimpleName(), ex.getMessage());
    return problems.onDomain(new InvalidRequestProblem(ex.getMessage()));
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
