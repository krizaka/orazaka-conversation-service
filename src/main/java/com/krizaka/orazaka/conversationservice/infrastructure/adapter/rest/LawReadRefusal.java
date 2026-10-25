package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.tools.domain.model.law.LawArticleRead;
import java.io.Serial;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * A law read that serves no text (issue #89), answered by {@link RestErrorResolver} as a Problem
 * Details body: {@code code} the outcome as a slug ({@code law-version-unknown}), {@code outcome}
 * its name in the corpus ({@code LAW_VERSION_UNKNOWN}, as the {@code lookupStatute} tool says it).
 * Not a krizaka-web {@code DomainException}: three of these outcomes are the platform's (5xx), not
 * the caller's.
 */
final class LawReadRefusal extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient HttpStatus status;
  private final String outcome;

  LawReadRefusal(HttpStatus status, LawArticleRead read, String message) {
    super(message);
    this.status = status;
    this.outcome = read.outcome().name();
  }

  HttpStatus status() {
    return status;
  }

  /** The outcome, as the corpus names it. */
  String outcome() {
    return outcome;
  }

  /** The outcome as a problem code slug. */
  String code() {
    return outcome.toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
