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
  private com.krizaka.orazaka.core.domain.ports.outbound.ChatMemoryStore chatMemoryStore;
  private ChatController controller;
  private SseEmitter mockEmitter;

  @BeforeEach
  void setUp() {
    aiClient = mock(AiClient.class);
    contextService = mock(ContextService.class);
    pipelineExecutor = mock(DynamicPipelineExecutor.class);
    sseStreamGateway = mock(SseStreamGateway.class);
    chatSessionService = mock(ChatSessionService.class);
    chatMemoryStore = mock(com.krizaka.orazaka.core.domain.ports.outbound.ChatMemoryStore.class);

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
            chatMemoryStore,
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

  // ── orazaka-conversation-service#17: a session is its owner's — BOLA/IDOR ──

  private static User userNamed(String name) {
    return new User(
        java.util.UUID.randomUUID(),
        name,
        name + "@test.class",
        true,
        Set.of("ROLE_USER"),
        Map.of());
  }

  private com.krizaka.orazaka.core.domain.model.chat.ChatSessionInfo sessionOf(User owner) {
    var session =
        new com.krizaka.orazaka.core.domain.model.chat.ChatSessionInfo(
            "sess-a", owner.id().toString(), "Private", java.time.Instant.EPOCH);
    when(chatSessionService.getSession("sess-a")).thenReturn(java.util.Optional.of(session));
    return session;
  }

  @Test
  void renamingAnotherUsersSessionIsNotFoundAndChangesNothing() {
    var alice = userNamed("alice");
    var bob = userNamed("bob");
    sessionOf(alice);

    var response = controller.renameSession("sess-a", Map.of("title", "pwned"), bob);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    verify(chatSessionService, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void renamingOwnSessionWorks() {
    var alice = userNamed("alice");
    sessionOf(alice);
    when(chatSessionService.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var response = controller.renameSession("sess-a", Map.of("title", "Mine"), alice);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().title()).isEqualTo("Mine");
  }

  @Test
  void deletingAnotherUsersSessionIsNotFoundAndDeletesNothing() {
    var alice = userNamed("alice");
    var bob = userNamed("bob");
    sessionOf(alice);

    var response = controller.deleteSession("sess-a", bob);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    verify(chatSessionService, org.mockito.Mockito.never()).deleteSession(anyString());
    verify(chatMemoryStore, org.mockito.Mockito.never()).clear(anyString());
  }

  @Test
  void deletingAMissingSessionLooksTheSameAsAForeignOne() {
    var response = controller.deleteSession("nope", userNamed("bob"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void deletingOwnSessionClearsItsMessagesToo() {
    var alice = userNamed("alice");
    sessionOf(alice);

    var response = controller.deleteSession("sess-a", alice);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(chatSessionService).deleteSession("sess-a");
    verify(chatMemoryStore).clear("sess-a");
  }

  @Test
  void creatingASessionOverAnotherUsersIdIsNotFoundAndKeepsTheOriginal() {
    var alice = userNamed("alice");
    var bob = userNamed("bob");
    sessionOf(alice);

    var response = controller.createSession(Map.of("conversationId", "sess-a"), bob);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    verify(chatSessionService, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void listingSessionsOnlyAsksForTheCallersOwn() {
    var bob = userNamed("bob");
    when(chatSessionService.getSessionsByUserId(anyString())).thenReturn(List.of());

    controller.getSessions(bob);

    verify(chatSessionService).getSessionsByUserId(bob.id().toString());
    verify(chatSessionService, org.mockito.Mockito.never())
        .getSessionsByUserId(
            org.mockito.ArgumentMatchers.argThat(id -> !id.equals(bob.id().toString())));
  }

  @Test
  void streamingIntoAnotherUsersConversationIsRefusedBeforeAnyHistoryIsRead() {
    var alice = userNamed("alice");
    var bob = userNamed("bob");
    sessionOf(alice);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.krizaka.web.problem.NotFoundException.class,
        () -> controller.streamChat("sess-a", "hi", bob));
    org.junit.jupiter.api.Assertions.assertThrows(
        com.krizaka.web.problem.NotFoundException.class,
        () ->
            controller.streamChatPost(
                "sess-a",
                new ChatStreamRequest("hi", List.of(), null, null, null, null, "default"),
                bob));
    verify(aiClient, org.mockito.Mockito.never()).stream(any(ChatRequest.class));
    verify(contextService, org.mockito.Mockito.never()).resolve(any(), any());
  }

  @Test
  void streamingIntoAnUnclaimedConversationClaimsItForTheCaller() {
    var bob = userNamed("bob");
    when(aiClient.stream(any(ChatRequest.class))).thenReturn(Flux.empty());

    controller.streamChat("fresh", "hi", bob);

    var saved =
        org.mockito.ArgumentCaptor.forClass(
            com.krizaka.orazaka.core.domain.model.chat.ChatSessionInfo.class);
    verify(chatSessionService).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo("fresh");
    assertThat(saved.getValue().userId()).isEqualTo(bob.id().toString());
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
