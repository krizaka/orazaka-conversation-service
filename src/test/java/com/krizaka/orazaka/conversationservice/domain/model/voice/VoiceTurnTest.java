package com.krizaka.orazaka.conversationservice.domain.model.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceTurnTest {

  @Test
  @DisplayName("Audio or text, exactly one")
  void audioOrText() {
    assertThatThrownBy(() -> new VoiceTurn("c", "fr-CA", null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new VoiceTurn("c", "fr-CA", new byte[] {1}, "bonjour", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new VoiceTurn("c", "fr-CA", null, " bonjour ", " ", null, " ").text())
        .isEqualTo("bonjour");
  }

  @Test
  @DisplayName("A locale is a BCP 47 tag; a conversation is named")
  void localeAndConversation() {
    assertThatThrownBy(() -> new VoiceTurn("c", "français", null, "x", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VoiceTurn(" ", "fr", null, "x", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new VoiceTurn("c", "nl-BE", null, "x", null, null, null).locale())
        .isEqualTo("nl-BE");
  }

  @Test
  @DisplayName("The recording and the context are bounded")
  void bounds() {
    assertThatThrownBy(
            () ->
                new VoiceTurn(
                    "c", "fr", new byte[VoiceTurn.MAX_AUDIO_BYTES + 1], null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new VoiceTurn(
                    "c",
                    "fr",
                    null,
                    "x",
                    null,
                    null,
                    "a".repeat(VoiceTurn.MAX_CONTEXT_CHARACTERS + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2 000 tokens");
  }

  @Test
  @DisplayName("Never logs what was said")
  void toStringKeepsNoContent() {
    VoiceTurn turn = new VoiceTurn("c", "fr", null, "je veux en finir", null, null, null);

    assertThat(turn.toString()).doesNotContain("finir");
    assertThat(turn)
        .isEqualTo(new VoiceTurn("c", "fr", null, "je veux en finir", null, null, null));
    assertThat(turn.hashCode())
        .isEqualTo(new VoiceTurn("c", "fr", null, "je veux en finir", null, null, null).hashCode());
  }
}
