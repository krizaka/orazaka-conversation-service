package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.users.domain.exception.InvalidRequestException;
import com.krizaka.web.KrizakaWebProperties;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RestErrorResolverTest {

  private final RestErrorResolver resolver =
      new RestErrorResolver(new ProblemDetailsAdvice(new KrizakaWebProperties(null, null)));

  @Test
  @DisplayName("An invalid request is krizaka-web's Problem Details: 400, code invalid-request")
  void invalidRequestMappedToBadRequest() {
    var problem =
        resolver.handleInvalidRequest(new InvalidRequestException("Model 'x' is not supported"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("Model 'x' is not supported");
    assertThat(problem.getType()).hasToString("https://krizaka.com/problems/invalid-request");
    assertThat(problem.getProperties()).containsEntry("code", "invalid-request");
    assertThat(problem.getProperties()).containsKey("requestId");
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
