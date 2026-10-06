package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller exposing the CLI agent SSE streaming endpoint for reverse AMQP tunneling.
 *
 * <p>When {@code orazaka-cli agent listen} connects, it establishes a persistent SSE stream. The
 * router authenticates via {@code orazaka-identity} and publishes a {@code cli.online} heartbeat to
 * RabbitMQ. Incoming automation payloads are proxied through the open tunnel to the CLI.
 */
@RestController
@RequestMapping("/api/v1/agent")
class AgentController {
  private static final Logger logger = LoggerFactory.getLogger(AgentController.class);

  private static final String KEY_USER_ID = "userId";
  private static final String KEY_STATUS = "status";

  /** Timeout for SSE connections: 30 minutes. */
  private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

  private final RabbitTemplate rabbitTemplate;
  private final ConcurrentHashMap<String, SseEmitter> activeAgents = new ConcurrentHashMap<>();

  AgentController(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  /**
   * Establishes a persistent SSE stream for the CLI agent. The agent connects outbound and receives
   * automation payloads through this tunnel.
   *
   * @param userId The authenticated user ID from the Bearer token.
   * @return An SSE emitter that proxies automation payloads to the CLI.
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter agentStream(@RequestHeader("X-User-Id") String userId) {
    logger.info("CLI agent connecting.");

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

    // Register the active agent connection
    activeAgents.put(userId, emitter);

    // Publish the agent presence heartbeat (AGENTS.md §6: evt.{aggregate}.{type})
    rabbitTemplate.convertAndSend(
        MessagingContract.EVENTS_EXCHANGE,
        MessagingContract.EVT_AGENT_PRESENCE,
        Map.of(KEY_USER_ID, userId, KEY_STATUS, "ONLINE", "timestamp", Instant.now().toString()));

    // Send initial connection confirmation
    try {
      emitter.send(
          SseEmitter.event()
              .name("connected")
              .data(Map.of(KEY_STATUS, "CONNECTED", KEY_USER_ID, userId)));
    } catch (IOException e) {
      logger.error("Failed to send initial SSE event to agent.", e);
    }

    // Cleanup on disconnect
    emitter.onCompletion(() -> cleanupAgent(userId));
    emitter.onTimeout(() -> cleanupAgent(userId));
    emitter.onError(e -> cleanupAgent(userId));

    logger.info("CLI agent registered and online.");
    return emitter;
  }

  /**
   * Receives execution reports from the CLI agent after local task completion.
   *
   * @param userId The authenticated user ID.
   * @param report The execution result report.
   * @return Acknowledgment of report receipt.
   */
  @PostMapping("/report")
  Map<String, Object> receiveReport(
      @RequestHeader("X-User-Id") String userId, @RequestBody Map<String, Object> report) {
    logger.info("Received agent execution report.");

    // Forward report to the automation worker via RabbitMQ (evt.agent.result.{userId})
    String routingKey = MessagingContract.EVT_AGENT_RESULT_PREFIX + userId;
    rabbitTemplate.convertAndSend(MessagingContract.EVENTS_EXCHANGE, routingKey, report);

    return Map.of(KEY_STATUS, "RECEIVED", "message", "Execution report forwarded to worker");
  }

  /**
   * Dispatches an automation payload through the active SSE tunnel to a specific user's CLI agent.
   *
   * @param userId Target user ID.
   * @param payload Automation payload to dispatch.
   * @return true if the payload was sent, false if no active agent.
   */
  boolean dispatchToAgent(String userId, Map<String, Object> payload) {
    SseEmitter emitter = activeAgents.get(userId);
    if (emitter == null) {
      logger.warn("No active CLI agent — cannot dispatch payload.");
      return false;
    }

    try {
      emitter.send(SseEmitter.event().name("automation_payload").data(payload));
      logger.info("Dispatched automation payload to CLI agent.");
      return true;
    } catch (IOException e) {
      logger.error("Failed to dispatch payload to CLI agent.", e);
      cleanupAgent(userId);
      return false;
    }
  }

  private void cleanupAgent(String userId) {
    activeAgents.remove(userId);
    rabbitTemplate.convertAndSend(
        MessagingContract.EVENTS_EXCHANGE,
        MessagingContract.EVT_AGENT_PRESENCE,
        Map.of(KEY_USER_ID, userId, KEY_STATUS, "OFFLINE", "timestamp", Instant.now().toString()));
    logger.info("CLI agent disconnected.");
  }
}
