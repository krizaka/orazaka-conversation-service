package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.core.domain.model.embedding.EmbeddingResult;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Answer of {@code POST /api/v1/embeddings}: OpenAI-shaped {@code data}, plus the Orazaka {@code
 * runId} — the identifier of this run in Orazaka's logs, which a caller keeps as proof of use.
 */
public record EmbeddingResponse(
    String runId, String model, int dimensions, List<Item> data, Usage usage) {

  /** One vector, at the index of its input. */
  public record Item(int index, float[] embedding) {

    @Override
    public boolean equals(Object other) {
      return other instanceof Item item
          && index == item.index
          && Arrays.equals(embedding, item.embedding);
    }

    @Override
    public int hashCode() {
      return 31 * Integer.hashCode(index) + Arrays.hashCode(embedding);
    }

    @Override
    public String toString() {
      return "Item[index=" + index + ", embedding=" + Arrays.toString(embedding) + "]";
    }
  }

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
