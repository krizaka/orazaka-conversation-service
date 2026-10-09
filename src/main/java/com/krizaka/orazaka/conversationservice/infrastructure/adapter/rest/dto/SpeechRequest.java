package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.users.domain.exception.InvalidRequestException;

/** Request DTO for text-to-speech generation containing text prompt and model parameters. */
public record SpeechRequest(String text, String model, String voice) {
  public SpeechRequest {
    if (text == null || text.isBlank()) {
      throw new InvalidRequestException("Text is required");
    }
  }
}
