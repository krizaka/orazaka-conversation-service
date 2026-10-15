package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.EmbeddingRequest;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.EmbeddingResponse;
import com.krizaka.orazaka.core.domain.model.embedding.EmbeddingException;
import com.krizaka.orazaka.core.domain.model.embedding.EmbeddingResult;
import com.krizaka.orazaka.core.domain.ports.outbound.EmbeddingClient;
import com.krizaka.users.domain.model.User;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for the {@code embeddings} resource: texts in, vectors out, with the platform's
 * own embedding model (the catalogue's {@code embedding} category — the caller never names a
 * provider). The product that calls it (an Explorer, a search) keeps the vectors and the {@code
 * runId}; each call is one audit line in Orazaka's logs.
 */
@RestController
@RequestMapping("/api/v1/embeddings")
public class EmbeddingController {

  private static final Logger logger = LoggerFactory.getLogger(EmbeddingController.class);

  /** Header carrying the run identifier, so a client that ignores the body can still log it. */
  static final String RUN_ID_HEADER = "X-Orazaka-Run-Id";

  private final EmbeddingClient embeddings;

  public EmbeddingController(EmbeddingClient embeddings) {
    this.embeddings = Objects.requireNonNull(embeddings, "EmbeddingClient must not be null");
  }

  @PostMapping
  public ResponseEntity<EmbeddingResponse> embed(
      @RequestBody EmbeddingRequest request, @AuthenticationPrincipal User user) {
    String runId = UUID.randomUUID().toString();
    EmbeddingResult result;
    try {
      result = embeddings.embed(request.input(), request.model());
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestProblem(e.getMessage());
    } catch (EmbeddingException e) {
      logger.warn("embedding.run runId={} status=unavailable", runId);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    // Ids and counts only — never an input text, never a vector.
    logger.info(
        "embedding.run runId={} user={} model={} inputs={} dimensions={} promptTokens={}",
        runId,
        user != null ? user.id() : "anonymous",
        result.model(),
        result.vectors().size(),
        result.dimensions(),
        result.promptTokens());
    return ResponseEntity.ok()
        .header(RUN_ID_HEADER, runId)
        .body(EmbeddingResponse.of(runId, result));
  }
}
