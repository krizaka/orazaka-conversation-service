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

  // ── orazaka-conversation-service#14: a gone client must stop the upstream generation ──

  /** A model that emits a token every 20 ms and counts what it emitted and whether it was cut. */
  private static final class SlowModel {
    final java.util.concurrent.atomic.AtomicInteger emitted =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();

    Flux<ChatResponse> stream() {
      return Flux.interval(java.time.Duration.ofMillis(20))
          .map(
              i -> {
                emitted.incrementAndGet();
                return new ChatResponse("t" + i, "conv-123", Map.of());
              })
          .doOnCancel(() -> cancelled.set(true));
    }
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition, long millis)
      throws InterruptedException {
    long deadline = System.nanoTime() + millis * 1_000_000;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }

  private void assertUpstreamStopsWithinOneSecond(SlowModel model) throws InterruptedException {
    awaitTrue(model.cancelled::get, 1000);
    assertThat(model.cancelled).as("upstream cancelled within 1 s").isTrue();
    int atCancel = model.emitted.get();
    Thread.sleep(200);
    assertThat(model.emitted.get()).as("no further tokens after the cancel").isEqualTo(atCancel);
  }

  @Test
  void aFailedSendBecauseTheClientLeftCancelsTheUpstream() throws Exception {
    SlowModel model = new SlowModel();
    when(aiClient.stream(any(ChatRequest.class))).thenReturn(model.stream());
    // the Early-Ack (1st send) succeeds, the 3rd token's send finds the connection closed
    java.util.concurrent.atomic.AtomicInteger sends =
        new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            inv -> {
              if (sends.incrementAndGet() > 3) {
                throw new IOException("Broken pipe");
              }
              return null;
            })
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    controller.streamChat("conv-123", "prompt", Persona.freeUser());

    assertUpstreamStopsWithinOneSecond(model);
  }

  @Test
  void emitterCompletionTimeoutAndErrorEachCancelTheUpstream() throws Exception {
    for (String hook : List.of("completion", "timeout", "error")) {
      setUp();
      SlowModel model = new SlowModel();
      when(aiClient.stream(any(ChatRequest.class))).thenReturn(model.stream());
      org.mockito.ArgumentCaptor<Runnable> runnable =
          org.mockito.ArgumentCaptor.forClass(Runnable.class);
      @SuppressWarnings("unchecked")
      org.mockito.ArgumentCaptor<java.util.function.Consumer<Throwable>> consumer =
          org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);

      controller.streamChat("conv-123", "prompt", Persona.freeUser());
      awaitTrue(() -> model.emitted.get() >= 2, 1000);
      assertThat(model.cancelled).as(hook + ": still running before the hook").isFalse();

      switch (hook) {
        case "completion" -> {
          verify(mockEmitter).onCompletion(runnable.capture());
          runnable.getValue().run();
        }
        case "timeout" -> {
          verify(mockEmitter).onTimeout(runnable.capture());
          runnable.getValue().run();
        }
        default -> {
          verify(mockEmitter).onError(consumer.capture());
          consumer.getValue().accept(new IOException("reset"));
        }
      }
      assertUpstreamStopsWithinOneSecond(model);
    }
  }

  @Test
  void aFinishedStreamDoesNotUnregisterANewerOneOnTheSameConversation() throws Exception {
    ChatStreamRegistry registry = new ChatStreamRegistry();
    SseEmitter first = mock(SseEmitter.class);
    SseEmitter second = mock(SseEmitter.class);
    registry.register("conv-123", first);
    registry.register("conv-123", second);

    registry.unregister("conv-123", first);

    // the second stream is still registered: a pipeline event reaches it
    registry.onPipelineStage(
        new com.krizaka.orazaka.core.domain.event.PipelineStageEvent("conv-123", "x"));
    verify(second).send(any(SseEmitter.SseEventBuilder.class));
  }
}
