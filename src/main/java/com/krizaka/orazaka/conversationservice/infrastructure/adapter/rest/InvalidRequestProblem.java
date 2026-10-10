package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.web.problem.DomainException;
import org.springframework.http.HttpStatus;

/**
 * An invalid request (unknown model, payload semantics) as krizaka-web's Problem Details: {@code
 * 400}, code {@value #CODE}.
 */
final class InvalidRequestProblem extends DomainException {

  private static final long serialVersionUID = 1L;

  /** The stable code a client may branch on. */
  static final String CODE = "invalid-request";

  InvalidRequestProblem(String message) {
    super(HttpStatus.BAD_REQUEST, CODE, message);
  }
}
