package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.conversationservice.application.service.ContextService;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.ChatStreamRequest;
import com.krizaka.orazaka.conversationservice.infrastructure.config.JobsProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.support.ChatStreamRegistry;
import com.krizaka.orazaka.conversationservice.infrastructure.support.SseStreamGateway;
import com.krizaka.orazaka.core.application.pipeline.DynamicPipelineExecutor;
import com.krizaka.orazaka.core.domain.model.AdvancedPipelineSchema;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.core.domain.ports.inbound.ChatSessionService;
import com.krizaka.users.domain.model.Persona;
import com.krizaka.users.domain.model.User;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

class ChatControllerTest {

  private AiClient aiClient;
  private ContextService contextService;
  private DynamicPipelineExecutor pipelineExecutor;
  private SseStreamGateway sseStreamGateway;
  private ChatSessionService chatSessionService;
  private ChatController controller;
  private SseEmitter mockEmitter;

  @BeforeEach
  void setUp() {
    aiClient = mock(AiClient.class);
    contextService = mock(ContextService.class);
    pipelineExecutor = mock(DynamicPipelineExecutor.class);
    sseStreamGateway = mock(SseStreamGateway.class);
    chatSessionService = mock(ChatSessionService.class);

    when(pipelineExecutor.buildSchema(any(), any()))
        .thenReturn(new AdvancedPipelineSchema("default", List.of(), List.of(), 0L));
    when(contextService.resolve(any(), any()))
        .thenReturn(new Context("user-id", "conv-123", Map.of(), Set.of()));

    mockEmitter = mock(SseEmitter.class);
    when(sseStreamGateway.createEmitter()).thenReturn(mockEmitter);

    controller =
        new ChatController(
            aiClient,
            contextService,
            pipelineExecutor,
            new ChatStreamRegistry(),
            sseStreamGateway,
            chatSessionService,
            new JobsProperties(30));
  }

  @Test
  void shouldFilterEmptyTokensButPreserveWhitespaceFromStream() throws IOException {
    User mockUser = Persona.freeUser();

    List<ChatResponse> responses =
        List.of(
            new ChatResponse("", "conv-123", Map.of()),
            new ChatResponse("   ", "conv-123", Map.of()),
            new ChatResponse("Hello", "conv-123", Map.of()),
            new ChatResponse("", "conv-123", Map.of()),
            new ChatResponse(" world!", "conv-123", Map.of()),
            new ChatResponse("   ", "conv-123", Map.of()));

    when(aiClient.stream(any(ChatRequest.class))).thenReturn(Flux.fromIterable(responses));

    SseEmitter result = controller.streamChat("conv-123", "prompt", mockUser);

    assertThat(result).isSameAs(mockEmitter);
    verify(mockEmitter, times(5)).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void shouldFilterEmptyAndBlankTokensOnPostStream() throws IOException {
    User mockUser = Persona.freeUser();

    List<ChatResponse> responses =
        List.of(
            new ChatResponse("Hello", "conv-123", Map.of()),
            new ChatResponse("", "conv-123", Map.of()));

    when(aiClient.stream(any(ChatRequest.class))).thenReturn(Flux.fromIterable(responses));

    ChatStreamRequest requestPayload =
        new ChatStreamRequest("prompt", List.of(), null, null, null, null, "default");
    SseEmitter result = controller.streamChatPost("conv-123", requestPayload, mockUser);

    assertThat(result).isSameAs(mockEmitter);
    verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void getSessions_returnsUserSessions() {
    User mockUser = Persona.freeUser();
    when(chatSessionService.getSessionsByUserId(anyString())).thenReturn(List.of());

    var response = controller.getSessions(mockUser);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEmpty();
    verify(chatSessionService).getSessionsByUserId(mockUser.id().toString());
  }
}
