package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import java.util.List;
import java.util.Objects;

/**
 * Request payload record for structured token-streaming chat requests.
 *
 * @param prompt The textual prompt.
 * @param assetIds Optional list of asset UUID references.
 * @param model Optional model override.
 * @param videoSteps Optional video generation steps.
 * @param videoFps Optional video frames per second.
 * @param motionBucketId Optional motion bucket identifier.
 * @param pipelineId Optional pipeline configuration identifier (defaults to {@code "default"}).
 */
public record ChatStreamRequest(
    String prompt,
    List<String> assetIds,
    String model,
    Integer videoSteps,
    Integer videoFps,
    Integer motionBucketId,
    String pipelineId) {
  public ChatStreamRequest {
    Objects.requireNonNull(prompt, "prompt must not be null");
    assetIds = (assetIds != null) ? List.copyOf(assetIds) : List.of();
    pipelineId = (pipelineId != null && !pipelineId.isBlank()) ? pipelineId : "default";
  }
}
