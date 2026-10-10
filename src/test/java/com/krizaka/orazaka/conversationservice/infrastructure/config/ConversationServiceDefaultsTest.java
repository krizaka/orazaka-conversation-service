package com.krizaka.orazaka.conversationservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ConversationServiceDefaultsTest {

  private static StandardEnvironment withDefaults(Map<String, Object> above) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("above", above));
    new ConversationServiceDefaults().postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  @Test
  void theOperatingDefaultsAreThere() {
    StandardEnvironment environment = withDefaults(Map.of());

    assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
        .isEqualTo("10");
    assertThat(environment.getProperty("spring.rabbitmq.listener.simple.prefetch")).isEqualTo("1");
    assertThat(environment.getProperty("spring.mvc.async.request-timeout")).isEqualTo("600000");
    assertThat(environment.getProperty("orazaka.router.capability-endpoints.paths[/api/v1/chat]"))
        .isEqualTo("orazaka.core.chat.completion");
  }

  @Test
  void everyOtherSourceWins() {
    StandardEnvironment environment =
        withDefaults(Map.of("spring.rabbitmq.listener.simple.prefetch", "5"));

    assertThat(environment.getProperty("spring.rabbitmq.listener.simple.prefetch")).isEqualTo("5");
    assertThat(environment.getPropertySources().stream().reduce((first, second) -> second))
        .hasValueSatisfying(
            last ->
                assertThat(last.getName())
                    .startsWith(ConversationServiceDefaults.PROPERTY_SOURCE_NAME));
  }

  @Test
  void runningTwiceAddsItOnce() {
    StandardEnvironment environment = withDefaults(Map.of());
    int sources = environment.getPropertySources().size();

    new ConversationServiceDefaults().postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getPropertySources().size()).isEqualTo(sources);
  }

  @Test
  void applicationYmlStaysAtMostTwentyLines() throws IOException {
    assertThat(Files.readAllLines(Path.of("src", "main", "resources", "application.yml")))
        .hasSizeLessThanOrEqualTo(20);
  }
}
