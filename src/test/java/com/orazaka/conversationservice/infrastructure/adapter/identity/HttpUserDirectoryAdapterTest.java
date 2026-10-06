package com.orazaka.conversationservice.infrastructure.adapter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.conversationservice.infrastructure.config.IdentityDirectoryProperties;
import com.orazaka.conversationservice.infrastructure.config.SessionJwtProperties;
import com.orazaka.identity.domain.model.User;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Behavioral round-trip of the user directory against a JDK stub of the identity internal API:
 * snapshot mapping, 404 handling, and the TTL cache collapsing repeated lookups.
 */
class HttpUserDirectoryAdapterTest {

  private static HttpServer stub;
  private static final AtomicInteger userHits = new AtomicInteger();
  private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440001";

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext(
        "/internal/v1/users/" + USER_ID + "/credentials/openai",
        exchange -> respond(exchange, 200, "{\"apiKey\":\"sk-decrypted\"}"));
    stub.createContext(
        "/internal/v1/users/" + USER_ID + "/credentials/missing",
        exchange -> respond(exchange, 404, "{}"));
    stub.createContext(
        "/internal/v1/users/" + USER_ID + "/profile", exchange -> respond(exchange, 404, "{}"));
    stub.createContext(
        "/internal/v1/users/" + USER_ID,
        exchange -> {
          userHits.incrementAndGet();
          respond(
              exchange,
              200,
              "{\"id\":\""
                  + USER_ID
                  + "\",\"username\":\"admin\",\"email\":\"a@o.com\","
                  + "\"enabled\":true,\"authorities\":[\"ROLE_ADMIN\"],"
                  + "\"preferences\":{\"language\":\"fr\"},"
                  + "\"activeInterceptions\":[],\"rateLimitTier\":\"admin\"}");
        });
    stub.createContext(
        "/internal/v1/tiers/default",
        exchange ->
            respond(
                exchange,
                200,
                "{\"tierKey\":\"free\",\"requestsPerMinute\":60,\"concurrentJobs\":1}"));
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private HttpUserDirectoryAdapter adapter() {
    return new HttpUserDirectoryAdapter(
        RestClient.builder(),
        new IdentityDirectoryProperties(
            "http://127.0.0.1:" + stub.getAddress().getPort(), Duration.ofMinutes(1)),
        new SessionJwtProperties("orazaka-test-secret-at-least-32-characters!"));
  }

  @Test
  @DisplayName("Hydrates the full User from the internal snapshot and caches it")
  void hydratesAndCachesUser() {
    HttpUserDirectoryAdapter adapter = adapter();
    userHits.set(0);

    User first = adapter.getUser(USER_ID);
    User second = adapter.getUser(USER_ID);

    assertThat(first.username()).isEqualTo("admin");
    assertThat(first.authorities()).containsExactly("ROLE_ADMIN");
    assertThat(first.preferences()).containsEntry("language", "fr");
    assertThat(first.rateLimitTier()).isEqualTo("admin");
    assertThat(second).isEqualTo(first);
    assertThat(userHits.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("A 404 profile resolves to null (provider contract)")
  void missingProfileIsNull() {
    assertThat(adapter().getProfile(USER_ID)).isNull();
  }

  @Test
  @DisplayName("Credentials: decrypted key when present, empty on 404")
  void credentialLookups() {
    HttpUserDirectoryAdapter adapter = adapter();

    assertThat(adapter.getDecryptedApiKey(USER_ID, "openai")).contains("sk-decrypted");
    assertThat(adapter.getDecryptedApiKey(USER_ID, "missing")).isEmpty();
  }

  @Test
  @DisplayName("Default tier resolves through the internal tiers endpoint")
  void defaultTierResolves() {
    assertThat(adapter().getDefaultTierKey()).contains("free");
  }
}
