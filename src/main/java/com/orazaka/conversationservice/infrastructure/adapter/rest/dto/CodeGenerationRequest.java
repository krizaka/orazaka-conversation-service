package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.users.domain.exception.InvalidRequestException;

/**
 * Request DTO representing the parameters for a code-generation task.
 *
 * @param prompt The prompt describing the desired application feature or workspace.
 * @param model The target LLM model name (optional).
 */
public record CodeGenerationRequest(String prompt, String model) {
  public CodeGenerationRequest {
    if (prompt == null || prompt.isBlank()) {
      throw new InvalidRequestException("Prompt is required");
    }
  }
}
