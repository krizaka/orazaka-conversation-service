package com.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentControllerTest {

  private RabbitTemplate rabbitTemplate;
  private AgentController controller;

  @BeforeEach
  void setUp() {
    rabbitTemplate = mock(RabbitTemplate.class);
    controller = new AgentController(rabbitTemplate);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testAgentStreamAndCleanupOnTimeout() throws Exception {
    String userId = "user-123";

    // Establishing stream
    SseEmitter emitter = controller.agentStream(userId);
    assertNotNull(emitter);

    // Verify online heartbeat sent
    ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
    verify(rabbitTemplate)
        .convertAndSend(
            eq(MessagingContract.EVENTS_EXCHANGE),
            eq(MessagingContract.EVT_AGENT_PRESENCE),
            mapCaptor.capture());
    Map<String, Object> heartbeat = mapCaptor.getValue();
    assertEquals("user-123", heartbeat.get("userId"));
    assertEquals("ONLINE", heartbeat.get("status"));
    assertNotNull(heartbeat.get("timestamp"));

    // Verify dispatch to active agent works
    assertTrue(controller.dispatchToAgent(userId, Map.of("key", "val")));

    // Verify dispatch to non-existent agent returns false
    assertFalse(controller.dispatchToAgent("no-agent", Map.of("x", "y")));
  }

  @Test
  void testReceiveReport() {
    String userId = "user-123";
    Map<String, Object> report = Map.of("exitCode", 0, "logs", "Success");

    Map<String, Object> response = controller.receiveReport(userId, report);

    assertEquals("RECEIVED", response.get("status"));
    verify(rabbitTemplate)
        .convertAndSend(
            MessagingContract.EVENTS_EXCHANGE,
            MessagingContract.EVT_AGENT_RESULT_PREFIX + "user-123",
            report);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testDispatchToAgentFailure() throws Exception {
    String userId = "user-123";
    SseEmitter mockEmitter = mock(SseEmitter.class);

    // Inject mock emitter via reflection into activeAgents
    Field field = AgentController.class.getDeclaredField("activeAgents");
    field.setAccessible(true);
    ConcurrentHashMap<String, SseEmitter> activeAgents =
        (ConcurrentHashMap<String, SseEmitter>) field.get(controller);
    activeAgents.put(userId, mockEmitter);

    // Force send to throw IOException
    doThrow(new IOException("Failing send"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    // Dispatching should fail, clean up, and return false
    boolean success = controller.dispatchToAgent(userId, Map.of("key", "val"));
    assertFalse(success);

    // Verify OFFLINE heartbeat sent during cleanup
    verify(rabbitTemplate)
        .convertAndSend(
            eq(MessagingContract.EVENTS_EXCHANGE),
            eq(MessagingContract.EVT_AGENT_PRESENCE),
            argThat((Map<String, Object> m) -> "OFFLINE".equals(m.get("status"))));
  }
}
