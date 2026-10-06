package com.orazaka.conversationservice.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CorsProperties}. */
class CorsPropertiesTest {

  @Test
  @DisplayName("CorsProperties stores CORS configurations")
  void graphqlCorsProperties() {
    var cors =
        new CorsProperties(
            List.of("http://localhost:3000"),
            List.of("GET", "POST"),
            List.of("Authorization"),
            true);
    assertEquals(List.of("http://localhost:3000"), cors.allowedOrigins());
    assertEquals(List.of("GET", "POST"), cors.allowedMethods());
    assertEquals(List.of("Authorization"), cors.allowedHeaders());
    assertTrue(cors.allowCredentials());
  }
}
