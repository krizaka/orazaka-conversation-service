package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

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
import com.krizaka.orazaka.core.domain.model.chat.ChatSessionInfo;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.core.domain.ports.inbound.ChatSessionService;
import com.krizaka.orazaka.core.domain.ports.outbound.ChatMemoryStore;
import com.krizaka.users.domain.model.User;
import com.krizaka.web.problem.NotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;

/**
 * REST controller for the {@code chat} resource: real-time SSE conversation streaming and chat
 * session (memory-block) management.
 *
 * <p>Consolidates the former ChatStream and ChatSession controllers. Code generation lives in its
 * own {@link CodeController} and media analysis in {@link MediaAnalysisController} (§2.7 protocol
 * segregation).
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

  private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

  /** Problem code of a conversation or session the caller does not own — or that does not exist. */
  public static final String SESSION_NOT_FOUND = "session-not-found";

  private static final String DEFAULT_TITLE = "New Memory Block";

  private final AiClient aiClient;
  private final ContextService contextService;
  private final DynamicPipelineExecutor pipelineExecutor;
  private final ChatStreamRegistry chatStreamRegistry;
  private final SseStreamGateway sseStreamGateway;
  private final ChatSessionService chatSessionService;
  private final ChatMemoryStore chatMemoryStore;
  private final int jobTimeoutSeconds;

  public ChatController(
      AiClient aiClient,
      ContextService contextService,
      DynamicPipelineExecutor pipelineExecutor,
      ChatStreamRegistry chatStreamRegistry,
      SseStreamGateway sseStreamGateway,
      ChatSessionService chatSessionService,
      ChatMemoryStore chatMemoryStore,
      JobsProperties jobsProperties) {
    this.aiClient = aiClient;
    this.contextService = contextService;
    this.pipelineExecutor = pipelineExecutor;
    this.chatStreamRegistry = chatStreamRegistry;
    this.sseStreamGateway = sseStreamGateway;
    this.chatSessionService =
        Objects.requireNonNull(chatSessionService, "ChatSessionService must not be null");
    this.chatMemoryStore =
        Objects.requireNonNull(chatMemoryStore, "ChatMemoryStore must not be null");
    this.jobTimeoutSeconds = jobsProperties.executionTimeout();
  }

  // ── Conversation streaming ────────────────────────────────────────────────

  /**
   * Streams chat via SSE using POST. The prompt travels in the body, never in the URL: a query
   * string reaches access logs, proxies and browser history (orazaka-conversation-service#19).
   */
  @PostMapping(
      value = "/stream/{conversationId}",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public SseEmitter streamChatPost(
      @PathVariable String conversationId,
      @RequestBody ChatStreamRequest requestPayload,
      @AuthenticationPrincipal User user) {
    logger.debug(
        "SSE POST stream: conversation={}, assetIds={}", conversationId, requestPayload.assetIds());
    claimConversation(conversationId, user);
    Map<String, Object> settings = new HashMap<>();
    settings.put("assetIds", requestPayload.assetIds());
    if (requestPayload.model() != null) {
      settings.put("model", requestPayload.model());
    }
    if (requestPayload.videoSteps() != null) {
      settings.put("videoSteps", requestPayload.videoSteps());
    }
    if (requestPayload.videoFps() != null) {
      settings.put("videoFps", requestPayload.videoFps());
    }
    if (requestPayload.motionBucketId() != null) {
      settings.put("motionBucketId", requestPayload.motionBucketId());
    }
    settings.put("pipelineId", requestPayload.pipelineId());
    return streamSse(
        conversationId,
        new ChatRequest(
            requestPayload.prompt(), null, settings, resolveContext(user, conversationId)),
        requestPayload.pipelineId());
  }

  /**
   * A conversation's history is keyed by its id alone, so streaming into an id is reading and
   * writing that history: it is allowed only to the session's owner. An id nobody owns yet becomes
   * the caller's; an id someone else owns is a 404, the same answer as for a missing one, so
   * existence never leaks (orazaka-conversation-service#17).
   */
  private void claimConversation(String conversationId, User user) {
    String userId = user.id().toString();
    var existing = chatSessionService.getSession(conversationId);
    if (existing.isPresent()) {
      if (!userId.equals(existing.get().userId())) {
        throw new NotFoundException(SESSION_NOT_FOUND, "No conversation " + conversationId + ".");
      }
      return;
    }
    chatSessionService.save(
        new ChatSessionInfo(conversationId, userId, DEFAULT_TITLE, Instant.now()));
  }

  /**
   * The session when it exists and is the caller's; empty otherwise (missing and foreign alike).
   */
  private Optional<ChatSessionInfo> ownedSession(String sessionId, User user) {
    return chatSessionService
        .getSession(sessionId)
        .filter(session -> user.id().toString().equals(session.userId()));
  }

  private Context resolveContext(User user, String conversationId) {
    return contextService.resolve(user, conversationId);
  }

  private SseEmitter streamSse(String conversationId, ChatRequest request) {
    return streamSse(conversationId, request, "default");
  }

  private SseEmitter streamSse(String conversationId, ChatRequest request, String pipelineId) {
    logger.debug("SSE stream starting: conversation={}, pipeline={}", conversationId, pipelineId);
    SseEmitter emitter = sseStreamGateway.createEmitter();
    // Track this emitter so per-interceptor pipeline events can be relayed to it live.
    chatStreamRegistry.register(conversationId, emitter);
    // The upstream generation must stop when the client goes away: keep the subscription and
    // dispose it on completion, timeout, error or a failed send (orazaka-conversation-service#14).
    // A Swap tolerates the dispose arriving before the subscription is assigned (synchronous
    // sources emit inside subscribe()).
    Disposable.Swap upstream = Disposables.swap();
    // One id per event ("<conversation>:<n>"): a repeated id would break Last-Event-ID resumption.
    java.util.concurrent.atomic.AtomicLong chunkIndex =
        new java.util.concurrent.atomic.AtomicLong();
    Runnable cleanup =
        () -> {
          upstream.dispose();
          chatStreamRegistry.unregister(conversationId, emitter);
        };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(
        error -> {
          logger.debug("SSE transport error [{}]: {}", conversationId, error.toString());
          cleanup.run();
        });

    try {
      // ── Early-Ack: push pipeline architecture metadata before LLM inference ──
      emitPipelineAck(emitter, conversationId, pipelineId, request.prompt(), chunkIndex);

      Flux<ChatResponse> stream =
          aiClient.stream(request).timeout(Duration.ofSeconds(jobTimeoutSeconds));

      logger.debug("Subscribing to flux stream for conversation {}", conversationId);
      upstream.update(
          stream.subscribe(
              response -> {
                String content = response.content();
                if (content != null && !content.isEmpty()) {
                  try {
                    emitter.send(
                        SseEmitter.event()
                            .id(conversationId + ":" + chunkIndex.getAndIncrement())
                            .name("message")
                            .data(Map.of("content", content), MediaType.APPLICATION_JSON));
                  } catch (IOException | IllegalStateException sendFailure) {
                    logger.debug(
                        "Client disconnected from SSE stream [{}] mid-stream", conversationId);
                    cleanup.run();
                  }
                }
              },
              error -> {
                logger.error("Failed to initialize SSE stream [{}]", conversationId, error);
                chatStreamRegistry.unregister(conversationId, emitter);
                try {
                  emitter.completeWithError(error);
                } catch (Exception ignored) {
                  logger.trace(
                      "Emitter already closed during error completion [{}]", conversationId);
                }
              },
              () -> {
                logger.debug("SSE stream completed [{}]", conversationId);
                chatStreamRegistry.unregister(conversationId, emitter);
                try {
                  emitter.complete();
                } catch (Exception ignored) {
                  logger.trace(
                      "Emitter already closed during normal completion [{}]", conversationId);
                }
              }));
    } catch (Exception e) {
      logger.error(
          "Exception thrown during streamSse setup for conversation {}", conversationId, e);
      try {
        emitter.completeWithError(e);
      } catch (Exception ignored) {
        logger.trace("Emitter already closed during setup error [{}]", conversationId);
      }
    }

    return emitter;
  }

  /**
   * Emits the Early-Ack SSE event containing the compiled pipeline architecture schema, before the
   * LLM begins generating tokens.
   */
  private void emitPipelineAck(
      SseEmitter emitter,
      String conversationId,
      String pipelineId,
      String prompt,
      java.util.concurrent.atomic.AtomicLong chunkIndex) {
    try {
      AdvancedPipelineSchema schema = pipelineExecutor.buildSchema(pipelineId, prompt);
      emitter.send(
          SseEmitter.event()
              .id(conversationId + ":" + chunkIndex.getAndIncrement())
              .name("pipeline-ack")
              .data(schema, MediaType.APPLICATION_JSON));
      logger.debug(
          "Early-Ack emitted: pipeline={}, interceptors={}",
          schema.pipelineId(),
          schema.totalInterceptorCount());
    } catch (IOException e) {
      logger.debug("Client disconnected before Early-Ack could be sent [{}]", conversationId);
    } catch (Exception e) {
      logger.warn("Failed to build/send Early-Ack pipeline schema — proceeding without it.", e);
    }
  }

  // ── Sessions (memory blocks) ──────────────────────────────────────────────

  @GetMapping("/sessions")
  public ResponseEntity<List<ChatSessionInfo>> getSessions(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(chatSessionService.getSessionsByUserId(user.id().toString()));
  }

  @PostMapping("/sessions")
  public ResponseEntity<ChatSessionInfo> createSession(
      @RequestBody Map<String, String> body, @AuthenticationPrincipal User user) {
    String id = body.get("conversationId");
    if (id == null || id.isBlank()) {
      id = UUID.randomUUID().toString();
    }
    // An id already taken by someone else must not be overwritten (an upsert is a takeover).
    var taken = chatSessionService.getSession(id);
    if (taken.isPresent() && !user.id().toString().equals(taken.get().userId())) {
      return ResponseEntity.notFound().build();
    }
    String title = body.getOrDefault("title", DEFAULT_TITLE);
    ChatSessionInfo session = new ChatSessionInfo(id, user.id().toString(), title, Instant.now());
    return ResponseEntity.ok(chatSessionService.save(session));
  }

  @PatchMapping("/sessions/{sessionId}")
  public ResponseEntity<ChatSessionInfo> renameSession(
      @PathVariable String sessionId,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal User user) {
    String title = body.get("title");
    if (title == null || title.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    return ownedSession(sessionId, user)
        .map(
            session -> {
              ChatSessionInfo updated =
                  new ChatSessionInfo(session.id(), session.userId(), title, Instant.now());
              return ResponseEntity.ok(chatSessionService.save(updated));
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/sessions/{sessionId}")
  public ResponseEntity<Void> deleteSession(
      @PathVariable String sessionId, @AuthenticationPrincipal User user) {
    if (ownedSession(sessionId, user).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    // The messages go with the session: they are keyed by the id alone, so orphans would be
    // readable by whoever next claims it.
    chatMemoryStore.clear(sessionId);
    chatSessionService.deleteSession(sessionId);
    return ResponseEntity.noContent().build();
  }
}
