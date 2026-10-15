package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import java.util.List;

/**
 * Body of {@code POST /api/v1/embeddings}.
 *
 * @param input the texts to embed (1 to 128, none blank)
 * @param model optional catalogue model name; the {@code embedding} category's default when absent
 */
public record EmbeddingRequest(List<String> input, String model) {}
