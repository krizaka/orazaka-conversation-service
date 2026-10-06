package com.orazaka.conversationservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionJwtPropertiesTest {

  @Test
  @DisplayName("A 256-bit secret is accepted; short/null secrets are rejected")
  void secretValidation() {
    assertThat(new SessionJwtProperties("unit-test-identity-jwt-secret-256bit-key!").secret())
        .isNotBlank();
    assertThatIllegalArgumentException().isThrownBy(() -> new SessionJwtProperties("short"));
    assertThatIllegalArgumentException().isThrownBy(() -> new SessionJwtProperties(null));
  }
}
