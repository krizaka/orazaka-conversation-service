package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.orazaka.conversationservice.application.service.ContextService;
import com.orazaka.conversationservice.infrastructure.adapter.rest.dto.ChatStreamRequest;
import com.orazaka.conversationservice.infrastructure.config.JobsProperties;
import com.orazaka.conversationservice.infrastructure.support.ChatStreamRegistry;
import com.orazaka.conversationservice.infrastructure.support.SseStreamGateway;
import com.orazaka.core.application.pipeline.DynamicPipelineExecutor;
import com.orazaka.core.domain.model.AdvancedPipelineSchema;
import com.orazaka.core.domain.model.Context;
import com.orazaka.core.domain.model.chat.ChatRequest;
import com.orazaka.core.domain.model.chat.ChatResponse;
import com.orazaka.core.domain.model.chat.ChatSessionInfo;
import com.orazaka.core.domain.ports.inbound.AiClient;
import com.orazaka.core.domain.ports.inbound.ChatSessionService;
import com.orazaka.identity.domain.model.User;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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

  private final AiClient aiClient;
  private final ContextService contextService;
  private final DynamicPipelineExecutor pipelineExecutor;
  private final ChatStreamRegistry chatStreamRegistry;
  private final SseStreamGateway sseStreamGateway;
  private final ChatSessionService chatSessionService;
  private final int jobTimeoutSeconds;

  public ChatController(
      AiClient aiClient,
      ContextService contextService,
      DynamicPipelineExecutor pipelineExecutor,
      ChatStreamRegistry chatStreamRegistry,
      SseStreamGateway sseStreamGateway,
      ChatSessionService chatSessionService,
      JobsProperties jobsProperties) {
    this.aiClient = aiClient;
    this.contextService = contextService;
    this.pipelineExecutor = pipelineExecutor;
    this.chatStreamRegistry = chatStreamRegistry;
    this.sseStreamGateway = sseStreamGateway;
    this.chatSessionService =
        Objects.requireNonNull(chatSessionService, "ChatSessionService must not be null");
    this.jobTimeoutSeconds = jobsProperties.executionTimeout();
  }

  // ── Conversation streaming ────────────────────────────────────────────────

  /** Streams chat via SSE using GET. */
  @GetMapping(value = "/stream/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamChat(
      @PathVariable String conversationId,
      @RequestParam String prompt,
      @AuthenticationPrincipal User user) {
    // The conversation, never the prompt: a log keeps no data class (ADR-064).
    logger.debug("SSE GET stream: conversation={}", conversationId);
    return streamSse(
        conversationId, new ChatRequest(prompt, null, null, resolveContext(user, conversationId)));
  }

  /** Streams chat via SSE using POST (supporting file reference mappings). */
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
    emitter.onCompletion(() -> chatStreamRegistry.unregister(conversationId));
    emitter.onTimeout(() -> chatStreamRegistry.unregister(conversationId));

    try {
      // ── Early-Ack: push pipeline architecture metadata before LLM inference ──
      emitPipelineAck(emitter, conversationId, pipelineId, request.prompt());

      Flux<ChatResponse> stream =
          aiClient.stream(request).timeout(Duration.ofSeconds(jobTimeoutSeconds));

      logger.debug("Subscribing to flux stream for conversation {}", conversationId);
      stream.subscribe(
          response -> {
            String content = response.content();
            if (content != null && !content.isEmpty()) {
              try {
                emitter.send(
                    SseEmitter.event()
                        .id(conversationId)
                        .name("message")
                        .data(Map.of("content", content), MediaType.APPLICATION_JSON));
              } catch (IOException ioException) {
                logger.debug("Client disconnected from SSE stream [{}] mid-stream", conversationId);
              }
            }
          },
          error -> {
            logger.error("Failed to initialize SSE stream [{}]", conversationId, error);
            chatStreamRegistry.unregister(conversationId);
            try {
              emitter.completeWithError(error);
            } catch (Exception ignored) {
              logger.trace("Emitter already closed during error completion [{}]", conversationId);
            }
          },
          () -> {
            logger.debug("SSE stream completed [{}]", conversationId);
            chatStreamRegistry.unregister(conversationId);
            try {
              emitter.complete();
            } catch (Exception ignored) {
              logger.trace("Emitter already closed during normal completion [{}]", conversationId);
            }
          });
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
      SseEmitter emitter, String conversationId, String pipelineId, String prompt) {
    try {
      AdvancedPipelineSchema schema = pipelineExecutor.buildSchema(pipelineId, prompt);
      emitter.send(
          SseEmitter.event()
              .id(conversationId)
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
    String title = body.getOrDefault("title", "New Memory Block");
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
    return chatSessionService
        .getSession(sessionId)
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
    chatSessionService.deleteSession(sessionId);
    return ResponseEntity.ok().build();
  }
}
