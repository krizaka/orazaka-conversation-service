package com.orazaka.conversationservice.infrastructure.adapter.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orazaka.business.domain.model.WorkflowContext;
import com.orazaka.core.domain.model.chat.ChatRequest;
import com.orazaka.core.domain.model.chat.ChatResponse;
import com.orazaka.core.domain.ports.inbound.AiClient;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowAdapterTest {

  @Mock private AiClient aiClient;

  private WorkflowAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new WorkflowAdapter(aiClient);
  }

  @Test
  @DisplayName("Maps WorkflowContext into Core ChatRequest with namespaced preferences")
  void executeSovereignPrompt_mapsContextAndCallsClient() {
    String userPrompt = "Check compliance";
    WorkflowContext workflowContext =
        new WorkflowContext(
            "session-123",
            "Mask PII data",
            "PRO",
            Set.of("RefinerInterceptor"),
            Set.of("MemoryInterceptor"),
            Map.of("orgId", "krizaka"));

    ChatResponse response = new ChatResponse("Masked Output", "session-123", Map.of());
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(response);

    String result = adapter.executeSovereignPrompt("user-42", userPrompt, workflowContext);

    assertEquals("Masked Output", result);

    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());

    ChatRequest capturedRequest = captor.getValue();
    assertEquals(userPrompt, capturedRequest.prompt());
    assertEquals(1, capturedRequest.messages().size());
    assertEquals("system", capturedRequest.messages().get(0).role());
    assertEquals("Mask PII data", capturedRequest.messages().get(0).content());
    assertEquals("session-123", capturedRequest.context().conversationId());
    assertEquals("user-42", capturedRequest.context().userId());

    // Verify namespaced preference mapping
    Map<String, Object> prefs = capturedRequest.context().preferences();
    assertEquals("session-123", prefs.get("orazaka.pipeline.contextId"));
    assertEquals("PRO", prefs.get("orazaka.user.tier"));
    assertTrue(
        ((Set<?>) prefs.get("orazaka.pipeline.forcedInterceptors")).contains("RefinerInterceptor"));
    assertTrue(
        ((Set<?>) prefs.get("orazaka.pipeline.skippedInterceptors")).contains("MemoryInterceptor"));
    assertEquals("krizaka", prefs.get("orazaka.user.meta.orgId"));
  }

  @Test
  @DisplayName("Returns empty string when AiClient returns null response")
  void executeSovereignPrompt_returnsEmptyOnNullResponse() {
    WorkflowContext workflowContext = WorkflowContext.minimal("ctx-1", "instructions");
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(null);

    String result = adapter.executeSovereignPrompt("user-99", "query", workflowContext);

    assertEquals("", result);
  }
}
