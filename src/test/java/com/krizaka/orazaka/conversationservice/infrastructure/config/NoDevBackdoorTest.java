package com.krizaka.orazaka.conversationservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * orazaka-conversation-service#41 (CWE-489): no authentication shortcut is compiled into the
 * production artifact. A profile misconfiguration must never turn a literal bearer value into an
 * administrator, so the source tree that ships holds no such token.
 */
class NoDevBackdoorTest {

  @Test
  @DisplayName("the shipped sources hold no mock bearer token and no mock authentication filter")
  void noMockAuthenticationInMain() throws IOException {
    List<Path> offenders;
    try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
      offenders =
          files
              .filter(Files::isRegularFile)
              .filter(
                  file -> {
                    try {
                      return file.getFileName().toString().startsWith("Mock")
                          || Files.readString(file).contains("user-mock");
                    } catch (IOException e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .toList();
    }
    assertThat(offenders).isEmpty();
  }
}
