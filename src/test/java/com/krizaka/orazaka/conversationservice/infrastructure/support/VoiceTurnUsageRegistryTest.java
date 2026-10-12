package com.krizaka.orazaka.conversationservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.core.domain.event.ChatCompletedEvent;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.chat.InternalChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.InternalChatResponse;
import com.krizaka.orazaka.core.domain.model.chat.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceTurnUsageRegistryTest {

  private static ChatCompletedEvent completed(Map<String, Object> preferences, TokenUsage usage) {
    Context context = new Context("u", "c", preferences, Set.of());
    return new ChatCompletedEvent(
        new InternalChatRequest("p", List.of(), null, context, true),
        new InternalChatResponse("answer", "c", usage, Map.of()));
  }

  @Test
  @DisplayName("The stream's usage reaches the turn that declared itself")
  void matchesTheDeclaredTurn() {
    VoiceTurnUsageRegistry registry = new VoiceTurnUsageRegistry();
    registry.expect("turn-1");

    registry.onChatCompleted(completed(Map.of(), TokenUsage.reported(1, 1, 2)));
    registry.onChatCompleted(
        completed(
            Map.of(VoiceTurnUsageRegistry.TURN_KEY, "turn-1"), TokenUsage.reported(10, 20, 30)));

    assertThat(registry.await("turn-1", Duration.ofMillis(10)).totalTokens()).isEqualTo(30);
  }

  @Test
  @DisplayName("No event, no number: unmeasured, never zero")
  void absentIsUnmeasured() {
    VoiceTurnUsageRegistry registry = new VoiceTurnUsageRegistry();
    registry.expect("turn-2");

    assertThat(registry.await("turn-2", Duration.ofMillis(10)).reported()).isFalse();
    assertThat(registry.await("never-expected", Duration.ofMillis(10)).reported()).isFalse();
    registry.expect("turn-3");
    registry.forget("turn-3");
    assertThat(registry.await("turn-3", Duration.ofMillis(10)).reported()).isFalse();
  }
}
