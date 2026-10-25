package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.tools.domain.model.law.LawReviewRefusal;
import java.io.Serial;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * A refusal of the law review queue (ADR-089 §5, §8), answered by {@link RestErrorResolver} as
 * Problem Details: the status and the {@code code} slug the use case named ({@code
 * proposal-not-approved}, {@code golden-minimum-not-met}, {@code law-promotion-off}…). Not a
 * krizaka-web {@code DomainException}: the switches that are off are the platform's (503), not the
 * caller's. The rules are the use cases'; this only translates them.
 */
final class LawReviewProblem extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient HttpStatus status;
  private final String code;

  LawReviewProblem(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  HttpStatus status() {
    return status;
  }

  String code() {
    return code;
  }

  /**
   * Runs a use case and translates its refusals: a {@link LawReviewRefusal} keeps its status and
   * code, an {@link IllegalArgumentException} is {@code 400 invalid-request}.
   *
   * @param call the use case
   * @param <T> what it answers
   * @return its answer
   */
  static <T> T translate(Supplier<T> call) {
    try {
      return call.get();
    } catch (LawReviewRefusal refusal) {
      throw of(refusal.code(), refusal.getMessage());
    } catch (IllegalArgumentException invalid) {
      throw new InvalidRequestProblem(invalid.getMessage());
    }
  }

  /**
   * The problem of a refusal code.
   *
   * @param code the code
   * @param detail what, for the administrator
   * @return the problem
   */
  static LawReviewProblem of(LawReviewRefusal.Code code, String detail) {
    return new LawReviewProblem(HttpStatus.valueOf(code.httpStatus()), code.slug(), detail);
  }
}
