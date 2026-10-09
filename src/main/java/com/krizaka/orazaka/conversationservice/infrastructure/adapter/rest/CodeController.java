package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.application.service.ContextService;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.CodeGenerationRequest;
import com.krizaka.orazaka.conversationservice.infrastructure.config.JobsProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.support.SseStreamGateway;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.users.domain.model.User;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * REST controller for the {@code code} resource: code generation via SSE streaming.
 *
 * <p>Renamed from {@code CodeStreamController}; the endpoint moves from {@code /api/v1/chat/code}
 * to its own {@code /api/v1/code} resource. Uses {@link SseStreamGateway} for SSE lifecycle
 * management.
 */
@RestController
@RequestMapping("/api/v1/code")
public class CodeController {

  private static final Logger logger = LoggerFactory.getLogger(CodeController.class);
  private static final String DEFAULT_CODE_MODEL = "qwen2.5-coder:7b";

  private final AiClient aiClient;
  private final ContextService contextService;
  private final ExecutorService virtualThreadExecutor;
  private final SseStreamGateway sseStreamGateway;
  private final int jobTimeoutSeconds;

  public CodeController(
      AiClient aiClient,
      ContextService contextService,
      ExecutorService virtualThreadExecutor,
      SseStreamGateway sseStreamGateway,
      JobsProperties jobsProperties) {
    this.aiClient = aiClient;
    this.contextService = contextService;
    this.virtualThreadExecutor = virtualThreadExecutor;
    this.sseStreamGateway = sseStreamGateway;
    this.jobTimeoutSeconds = jobsProperties.executionTimeout();
  }

  /** Streams code generation via SSE using POST. */
  @PostMapping(
      produces = MediaType.TEXT_EVENT_STREAM_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public SseEmitter streamCode(
      @RequestBody CodeGenerationRequest requestPayload, @AuthenticationPrincipal User user) {
    // The model, never the prompt (ADR-064).
    logger.debug("SSE code stream request: model={}", requestPayload.model());

    SseEmitter emitter = sseStreamGateway.createEmitter();

    virtualThreadExecutor.submit(
        () -> {
          try {
            Context context = contextService.resolve(user, null);
            String model =
                (requestPayload.model() != null && !requestPayload.model().isBlank())
                    ? requestPayload.model()
                    : DEFAULT_CODE_MODEL;
            Map<String, Object> settings = Map.of("provider", "ollama", "model", model);
            ChatRequest request = new ChatRequest(requestPayload.prompt(), null, settings, context);
            Flux<ChatResponse> stream =
                aiClient.stream(request).timeout(Duration.ofSeconds(jobTimeoutSeconds));
            sseStreamGateway.subscribe(emitter, stream, "code-gen");
          } catch (Exception e) {
            logger.error("Failed to initialize SSE code stream", e);
            try {
              emitter.completeWithError(e);
            } catch (Exception ignored) {
              logger.trace("Emitter already closed during code stream error", ignored);
            }
          }
        });

    return emitter;
  }
}
