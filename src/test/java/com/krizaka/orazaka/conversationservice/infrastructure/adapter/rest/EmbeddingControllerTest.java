package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.EmbeddingRequest;
import com.krizaka.orazaka.core.domain.model.embedding.EmbeddingException;
import com.krizaka.orazaka.core.domain.model.embedding.EmbeddingResult;
import com.krizaka.orazaka.core.domain.ports.outbound.EmbeddingClient;
import com.krizaka.users.domain.model.User;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmbeddingControllerTest {

  private final EmbeddingClient client = mock(EmbeddingClient.class);
  private final EmbeddingController controller = new EmbeddingController(client);

  private static User user() {
    return new User(
        UUID.randomUUID(),
        "svc",
        "svc@orazaka.com",
        true,
        Set.of("ROLE_USER"),
        Map.of(),
        List.of(),
        "free");
  }

  @Test
  void answersTheVectorsWithARunIdInBodyAndHeader() {
    when(client.embed(List.of("a", "b"), null))
        .thenReturn(
            new EmbeddingResult("bge-m3", List.of(new float[] {1f, 2f}, new float[] {3f, 4f}), 5));

    var response = controller.embed(new EmbeddingRequest(List.of("a", "b"), null), user());

    var body = response.getBody();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body.runId()).isNotBlank();
    assertThat(response.getHeaders().getFirst("X-Orazaka-Run-Id")).isEqualTo(body.runId());
    assertThat(body.model()).isEqualTo("bge-m3");
    assertThat(body.dimensions()).isEqualTo(2);
    assertThat(body.data()).hasSize(2);
    assertThat(body.data().get(1).index()).isEqualTo(1);
    assertThat(body.data().get(1).embedding()).containsExactly(3f, 4f);
    assertThat(body.usage().promptTokens()).isEqualTo(5);
  }

  @Test
  void aBadBatchIsA400() {
    when(client.embed(List.of(), null))
        .thenThrow(new IllegalArgumentException("at least one input is required"));

    assertThatThrownBy(() -> controller.embed(new EmbeddingRequest(List.of(), null), user()))
        .isInstanceOf(InvalidRequestProblem.class);
  }

  @Test
  void noModelOrAProviderOutageIsA503() {
    when(client.embed(List.of("a"), null))
        .thenThrow(new EmbeddingException("no embedding model is configured"));

    assertThatThrownBy(() -> controller.embed(new EmbeddingRequest(List.of("a"), null), user()))
        .isInstanceOfSatisfying(
            org.springframework.web.server.ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }
}
