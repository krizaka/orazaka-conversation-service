package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.orazaka.identity.domain.exception.InvalidRequestException;

/** Request DTO for image generation containing prompt and model parameters. */
public record ImageGenerationRequest(String prompt, String model) {
  public ImageGenerationRequest {
    if (prompt == null || prompt.isBlank()) {
      throw new InvalidRequestException("Prompt is required");
    }
  }
}
