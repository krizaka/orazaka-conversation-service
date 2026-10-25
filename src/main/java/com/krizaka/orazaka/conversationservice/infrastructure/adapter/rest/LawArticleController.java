package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawArticleResponse;
import com.krizaka.orazaka.tools.application.service.LawArticleReadService;
import com.krizaka.orazaka.tools.domain.model.law.LawArticleQuery;
import com.krizaka.orazaka.tools.domain.model.law.LawArticleRead;
import com.krizaka.users.domain.model.User;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The law read API (issue #89, ADR-089 §2 and §9): {@code GET /api/v1/law/articles} — the text of
 * an article as it was in force on a day, from the dated, hashed corpus.
 *
 * <p>Through the engine's single door (ADR-068): the edge exchanges the caller's {@code oz_} key
 * for a session, this service authenticates it, and the read is one metered {@code RESEARCH} {@code
 * CALL}. Read-only. Every rule — {@code asOf} required, the hash check, the quote check, the
 * metering — is {@link LawArticleReadService}'s; this controller only translates HTTP.
 *
 * <ul>
 *   <li>{@code 200} the article: {@code {text, contentHash, inForceFrom, officialUrl, importId,
 *       quote}}, the read id in {@value #READ_ID_HEADER};
 *   <li>{@code 400 invalid-request} a part of the question missing or unreadable ({@code asOf}
 *       included);
 *   <li>{@code 402 law-budget-exceeded} the caller's budget cannot hold the call;
 *   <li>{@code 404 law-version-unknown} no version in force that day;
 *   <li>{@code 500 law-integrity-error} the stored text no longer gives its recorded hash: no text;
 *   <li>{@code 503 law-corpus-disabled} the operator switched the corpus off ({@code
 *       law.corpus.enabled}), and {@code 503 law-metering-unavailable} no meter is wired.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/law/articles")
public class LawArticleController {

  /** The identifier the read was metered under. */
  static final String READ_ID_HEADER = "X-Orazaka-Read-Id";

  private final LawArticleReadService reads;

  public LawArticleController(LawArticleReadService reads) {
    this.reads = Objects.requireNonNull(reads, "LawArticleReadService must not be null");
  }

  /**
   * Reads an article as it was in force on a day.
   *
   * @param jurisdiction {@code CA} or {@code QC}
   * @param instrument the instrument code, such as {@code CCQ-1991}
   * @param ref the article reference, such as {@code 1931}
   * @param language {@code fr} or {@code en}
   * @param asOf the day, ISO {@code yyyy-MM-dd}; required
   * @param quote words to check against the article text; optional
   * @param user the caller, who is billed
   * @return the article, or a problem naming the outcome
   */
  @GetMapping
  public ResponseEntity<LawArticleResponse> read(
      @RequestParam(required = false) String jurisdiction,
      @RequestParam(required = false) String instrument,
      @RequestParam(required = false) String ref,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String quote,
      @AuthenticationPrincipal User user) {
    LawArticleRead read;
    try {
      read =
          reads.read(
              new LawArticleQuery(jurisdiction, instrument, ref, language, asOf, quote),
              user.id().toString());
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestProblem(e.getMessage());
    }
    return switch (read.outcome()) {
      case FOUND ->
          ResponseEntity.ok()
              .header(READ_ID_HEADER, read.readId())
              .body(LawArticleResponse.of(read));
      case LAW_VERSION_UNKNOWN ->
          throw new LawReadRefusal(
              HttpStatus.NOT_FOUND, read, "No version of this article is known for that day.");
      case LAW_CORPUS_DISABLED ->
          throw new LawReadRefusal(
              HttpStatus.SERVICE_UNAVAILABLE, read, "The law corpus is switched off.");
      case LAW_INTEGRITY_ERROR ->
          throw new LawReadRefusal(
              HttpStatus.INTERNAL_SERVER_ERROR,
              read,
              "The stored text of this article failed its integrity check; it is not served.");
      case LAW_BUDGET_EXCEEDED ->
          throw new LawReadRefusal(
              HttpStatus.PAYMENT_REQUIRED, read, "The budget cannot hold this read.");
      case LAW_METERING_UNAVAILABLE ->
          throw new LawReadRefusal(
              HttpStatus.SERVICE_UNAVAILABLE, read, "Reads are not metered here: none is served.");
    };
  }
}
