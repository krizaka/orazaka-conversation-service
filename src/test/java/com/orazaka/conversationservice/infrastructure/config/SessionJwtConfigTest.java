package com.orazaka.conversationservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionJwtConfigTest {

  @Test
  @DisplayName("Builds the HS256 decoder over the shared secret")
  void buildsDecoder() {
    var decoder =
        new SessionJwtConfig()
            .identityJwtDecoder(
                new SessionJwtProperties("unit-test-identity-jwt-secret-256bit-key!"));
    assertThat(decoder).isNotNull();
  }
}
