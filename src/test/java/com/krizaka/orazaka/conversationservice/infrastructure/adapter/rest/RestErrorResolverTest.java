package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.users.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RestErrorResolverTest {

  private final RestErrorResolver resolver = new RestErrorResolver();

  @Test
  @DisplayName("An invalid request is mapped to 400 with the message in the error body")
  void invalidRequestMappedToBadRequest() {
    var response =
        resolver.handleInvalidRequest(new InvalidRequestException("Model 'x' is not supported"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("error", "Model 'x' is not supported");
  }

  @Test
  @DisplayName(
      "[ADR-062] an engine switched off is 503, never a plan upgrade the caller cannot fix")
  void aDisabledEngineIs503() {
    var response =
        resolver.handlePipelineDisabled(
            new com.krizaka.orazaka.core.application.pipeline.PipelineDisabledException());

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    org.assertj.core.api.Assertions.assertThat(response.getBody())
        .containsEntry("status", "engine_disabled")
        .doesNotContainKey("remedies");
  }
}
