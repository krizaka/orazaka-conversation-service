package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore.Erased;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProtectedConversationControllerTest {

  private final ProtectedConversationStore service = mock(ProtectedConversationStore.class);
  private final ProtectedConversationController controller =
      new ProtectedConversationController(service);

  @Test
  @DisplayName("erasing answers with counts only")
  void answersWithCounts() {
    when(service.eraseOf("actor-1", "bien-etre")).thenReturn(new Erased(2, 4));

    var response =
        controller.erase(
            new ProtectedConversationController.ErasureRequest("actor-1", "bien-etre"));

    assertThat(response).isEqualTo(new ProtectedConversationController.ErasureResponse(2, 4));
  }
}
