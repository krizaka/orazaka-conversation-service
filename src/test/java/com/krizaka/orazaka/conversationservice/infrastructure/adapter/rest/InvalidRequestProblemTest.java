package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InvalidRequestProblemTest {

  @Test
  void isA400WithItsCodeAndTheMessageAsDetail() {
    InvalidRequestProblem problem = new InvalidRequestProblem("Model 'x' is not supported");

    assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(problem.code()).isEqualTo("invalid-request");
    assertThat(problem.getMessage()).isEqualTo("Model 'x' is not supported");
  }
}
