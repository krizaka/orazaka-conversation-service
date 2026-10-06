package com.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.orazaka.business.api.Capability;
import com.orazaka.business.api.ChatPayload;
import com.orazaka.business.api.Intention;
import com.orazaka.business.api.IntentionType;
import com.orazaka.business.api.UseCaseDispatcher;
import com.orazaka.business.api.UseCaseResolutionException;
import com.orazaka.conversationservice.application.service.GateService;
import com.orazaka.conversationservice.infrastructure.adapter.rest.dto.IntentionRequest;
import com.orazaka.core.domain.model.chat.ChatResponse;
import com.orazaka.identity.domain.model.User;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class IntentControllerTest {

  private final GateService gateEvaluator = mock(GateService.class);
  private final UseCaseDispatcher dispatcher = mock(UseCaseDispatcher.class);
  private final IntentController controller = new IntentController(gateEvaluator, dispatcher);

  private static User user() {
    return new User(
        UUID.randomUUID(),
        "alice",
        "alice@orazaka.com",
        true,
        Set.of("ROLE_USER"),
        Map.of("language", "en"),
        List.of(),
        "free");
  }

  @Test
  void chat_mapsToChatQueryIntention_andDispatches() {
    doReturn(new ChatResponse("answer", "s1", Map.of())).when(dispatcher).dispatch(any());

    var response =
        controller.submit(new IntentionRequest("chat", "greet", "hello", "s1", null), user());

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<Intention> captor = ArgumentCaptor.forClass(Intention.class);
    verify(dispatcher).dispatch(captor.capture());
    Intention intention = captor.getValue();
    assertEquals(Capability.CHAT, intention.capability());
    assertEquals(IntentionType.QUERY, intention.type());
    assertInstanceOf(ChatPayload.class, intention.payload());
    assertEquals("hello", ((ChatPayload) intention.payload()).prompt());
    assertEquals(Set.of("ROLE_USER"), intention.context().authorities());
    assertEquals("s1", intention.context().sessionId());
    // ADR-064: this builder feeds the same engine Context as ContextService does.
    assertEquals(Map.of("preference.language", "en"), intention.context().preferences());
  }

  @Test
  void unknownCapability_yields400() {
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.submit(new IntentionRequest("bogus", null, "p", null, null), user()));
    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void unresolvedUseCase_yields404() {
    doThrow(new UseCaseResolutionException("ERR-410: no use-case"))
        .when(dispatcher)
        .dispatch(any());
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.submit(
                    new IntentionRequest("image", null, "a fox", null, null), user()));
    assertEquals(404, ex.getStatusCode().value());
  }
}
