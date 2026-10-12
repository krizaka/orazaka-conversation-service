package com.krizaka.orazaka.conversationservice.infrastructure.support;

import com.krizaka.web.problem.DomainException;
import org.springframework.http.HttpStatus;

/**
 * A voice turn refused before it started — answered as a Problem Details with a stable code
 * (ADR-076).
 */
public class VoiceTurnRefusedException extends DomainException {

  private static final long serialVersionUID = 1L;

  /**
   * @param status the HTTP status
   * @param code the stable code a client branches on
   * @param message what was wrong, never what the person said
   */
  public VoiceTurnRefusedException(HttpStatus status, String code, String message) {
    super(status, code, message);
  }
}
