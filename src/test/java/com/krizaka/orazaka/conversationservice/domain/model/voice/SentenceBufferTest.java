package com.krizaka.orazaka.conversationservice.domain.model.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SentenceBufferTest {

  private static List<String> feed(SentenceBuffer buffer, String... deltas) {
    List<String> out = new ArrayList<>();
    for (String delta : deltas) {
      out.addAll(buffer.accept(delta));
    }
    return out;
  }

  @Test
  @DisplayName("A sentence is cut once its punctuation is followed by a space, token by token")
  void cutsOnStrongPunctuation() {
    SentenceBuffer buffer = new SentenceBuffer();

    assertThat(
            feed(
                buffer,
                "Je compr",
                "ends que la semaine",
                " a été lourde",
                ".",
                " Qu'est",
                "-ce qui a pesé ?"))
        .containsExactly("Je comprends que la semaine a été lourde.");
    assertThat(buffer.flush()).contains("Qu'est-ce qui a pesé ?");
    assertThat(buffer.flush()).isEmpty();
  }

  @Test
  @DisplayName("Exclamation, question, ellipsis and a closing guillemet end a sentence")
  void otherEnds() {
    SentenceBuffer buffer = new SentenceBuffer();

    assertThat(
            feed(
                buffer,
                "C'est beaucoup pour une semaine ! Tu as dit « je n'en peux plus… » ce soir. Et ensuite "))
        .containsExactly(
            "C'est beaucoup pour une semaine !", "Tu as dit « je n'en peux plus… » ce soir.");
  }

  @Test
  @DisplayName("A number, an abbreviation and a too-short sentence are not cuts")
  void notCuts() {
    SentenceBuffer buffer = new SentenceBuffer();

    assertThat(
            feed(
                buffer,
                "Il est 9.5 sur 10, dit M. Tremblay, etc. et voilà. Oui. D'accord, on continue. "))
        .containsExactly(
            "Il est 9.5 sur 10, dit M. Tremblay, etc. et voilà.", "Oui. D'accord, on continue.");
  }

  @Test
  @DisplayName("A line break ends a list item")
  void lineBreaks() {
    SentenceBuffer buffer = new SentenceBuffer();

    assertThat(feed(buffer, "- garder le jeudi soir\n- en parler à quelqu'un\n"))
        .containsExactly("- garder le jeudi soir", "- en parler à quelqu'un");
  }

  @Test
  @DisplayName("The last character is never cut: it may still become a decimal")
  void waitsForTheNextCharacter() {
    SentenceBuffer buffer = new SentenceBuffer();

    assertThat(feed(buffer, "La note était de 9.")).isEmpty();
    assertThat(feed(buffer, "5 ce jour-là, vraiment. "))
        .containsExactly("La note était de 9.5 ce jour-là, vraiment.");
  }

  @Test
  @DisplayName("Nothing in, nothing out")
  void emptyDeltas() {
    SentenceBuffer buffer = new SentenceBuffer();

    assertThat(buffer.accept(null)).isEmpty();
    assertThat(buffer.accept("")).isEmpty();
    assertThat(buffer.flush()).isEmpty();
  }
}
