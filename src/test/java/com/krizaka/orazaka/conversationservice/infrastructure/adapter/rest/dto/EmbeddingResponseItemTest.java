package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingResponseItemTest {

  @Test
  @DisplayName("two vectors are equal when they hold the same numbers")
  void comparesVectorContent() {
    var a = new EmbeddingResponse.Item(0, new float[] {1f, 2f});
    var b = new EmbeddingResponse.Item(0, new float[] {1f, 2f});
    var c = new EmbeddingResponse.Item(0, new float[] {1f, 3f});
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
    assertThat(a.toString()).contains("[1.0, 2.0]");
  }
}
