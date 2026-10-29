package com.krizaka.orazaka.conversationservice.infrastructure.support;

import com.krizaka.orazaka.core.domain.event.PipelineStageEvent;
import com.krizaka.orazaka.core.domain.event.ToolExecutedEvent;
import java.io.IOException;
import java.util.HashMap;
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
   * Removes the emitter only if it is still the one registered: a second stream opened on the same
   * conversation must not be unregistered by the first one finishing.
   */
  public void unregister(String conversationId, SseEmitter emitter) {
    emitters.remove(conversationId, emitter);
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

  /**
   * Relays a tool the engine ran during the turn to the conversation's live SSE stream as a {@code
   * tool-call} event: the tool's name and call id only, never its arguments or result (ADR-064).
   * No-op if no stream is registered.
   *
   * @param event The tool-run event published by the engine.
   */
  @EventListener
  public void onToolExecuted(ToolExecutedEvent event) {
    SseEmitter emitter = emitters.get(event.conversationId());
    if (emitter == null) {
      return;
    }
    Map<String, Object> data = new HashMap<>();
    data.put("tool", event.toolName());
    data.put("callId", event.callId());
    try {
      emitter.send(
          SseEmitter.event()
              .id(event.conversationId())
              .name("tool-call")
              .data(data, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      logger.debug("Chat SSE [{}] closed before tool-call could be sent.", event.conversationId());
      emitters.remove(event.conversationId());
    }
  }
}
