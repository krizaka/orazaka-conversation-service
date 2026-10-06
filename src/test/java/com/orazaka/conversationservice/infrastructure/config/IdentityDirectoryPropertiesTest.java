package com.orazaka.conversationservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentityDirectoryPropertiesTest {

  @Test
  @DisplayName("Valid wiring is accepted")
  void validWiring() {
    var properties =
        new IdentityDirectoryProperties("http://identity:8083", Duration.ofSeconds(60));
    assertThat(properties.baseUrl()).isEqualTo("http://identity:8083");
  }

  @Test
  @DisplayName("Blank base-url and negative ttl are rejected")
  void invalidWiringRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new IdentityDirectoryProperties(" ", Duration.ofSeconds(60)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new IdentityDirectoryProperties("http://identity:8083", Duration.ofSeconds(-1)));
  }
}
