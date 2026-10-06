package com.orazaka.conversationservice.infrastructure.support;

import com.orazaka.core.domain.event.PipelineStageEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tracks the live chat {@link SseEmitter} per conversation and relays in-process {@link
 * PipelineStageEvent}s to it as a {@code pipeline-step} SSE event — so the UI marks each
 * interceptor complete in real time, as the pipeline runs (before the LLM tokens).
 *
 * <p>This is the synchronous, in-process bridge between the core pipeline and the chat SSE stream:
 * the core publishes a Spring application event per interceptor (transport-agnostic), and this
 * router-side relay routes it to the right emitter by conversation id. Chat never goes through the
 * broker (AGENTS §6).
 */
@Component
public class ChatStreamRegistry {

  private static final Logger logger = LoggerFactory.getLogger(ChatStreamRegistry.class);

  private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

  /** Associates the active SSE emitter with a conversation for the duration of its stream. */
  public void register(String conversationId, SseEmitter emitter) {
    emitters.put(conversationId, emitter);
  }

  /** Removes the emitter once its stream completes (or fails). */
  public void unregister(String conversationId) {
    emitters.remove(conversationId);
  }

  /**
   * Relays a completed interceptor stage to the conversation's live SSE stream. No-op if no stream
   * is registered (e.g. the synchronous non-streaming chat path).
   *
   * @param event The pipeline stage event published by the core executor.
   */
  @EventListener
  public void onPipelineStage(PipelineStageEvent event) {
    SseEmitter emitter = emitters.get(event.conversationId());
    if (emitter == null) {
      return;
    }
    try {
      emitter.send(
          SseEmitter.event()
              .id(event.conversationId())
              .name("pipeline-step")
              .data(Map.of("interceptorId", event.interceptorId()), MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      logger.debug(
          "Chat SSE [{}] closed before pipeline-step could be sent.", event.conversationId());
      emitters.remove(event.conversationId());
    }
  }
}
