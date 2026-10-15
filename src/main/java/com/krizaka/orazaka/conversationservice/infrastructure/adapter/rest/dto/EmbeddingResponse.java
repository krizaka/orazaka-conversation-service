package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.core.domain.model.embedding.EmbeddingResult;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Answer of {@code POST /api/v1/embeddings}: OpenAI-shaped {@code data}, plus the Orazaka {@code
 * runId} — the identifier of this run in Orazaka's logs, which a caller keeps as proof of use.
 */
public record EmbeddingResponse(
    String runId, String model, int dimensions, List<Item> data, Usage usage) {

  /** One vector, at the index of its input. */
  public record Item(int index, float[] embedding) {}

  /** Tokens the provider billed for the batch. */
  public record Usage(int promptTokens) {}

  public static EmbeddingResponse of(String runId, EmbeddingResult result) {
    return new EmbeddingResponse(
        runId,
        result.model(),
        result.dimensions(),
        IntStream.range(0, result.vectors().size())
            .mapToObj(i -> new Item(i, result.vectors().get(i)))
            .toList(),
        new Usage(result.promptTokens()));
  }
}
