package com.krizaka.orazaka.conversationservice.infrastructure.adapter.workflow;

import com.krizaka.orazaka.business.domain.model.WorkflowContext;
import com.krizaka.orazaka.business.domain.port.WorkflowOrchestrator;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Router adapter translating the Business layer's {@link WorkflowContext} into the Core
 * infrastructure's {@link Context} and {@link ChatRequest}.
 *
 * <p>This is the <b>sole translation boundary</b> between the Business hexagon (which owns "What")
 * and the Core engine (which owns "How"). All business context fields are mapped into the Core's
 * {@code Context.preferences} map using namespaced keys:
 *
 * <ul>
 *   <li>{@code orazaka.pipeline.contextId} — workflow execution identifier
 *   <li>{@code orazaka.pipeline.forcedInterceptors} — interceptor keys that must execute
 *   <li>{@code orazaka.pipeline.skippedInterceptors} — interceptor keys to bypass
 *   <li>{@code orazaka.user.tier} — subscription/RBAC tier
 *   <li>{@code orazaka.user.meta.*} — arbitrary business metadata entries
 * </ul>
 *
 * @see WorkflowOrchestrator
 * @see WorkflowContext
 * @since 1.0.0
 */
@Component
public class WorkflowAdapter implements WorkflowOrchestrator {

  private final AiClient aiClient;

  public WorkflowAdapter(AiClient aiClient) {
    this.aiClient = aiClient;
  }

  @Override
  public String executeSovereignPrompt(
      String userId, String userPrompt, WorkflowContext workflowContext) {
    Objects.requireNonNull(userId, "userId must not be null — V3.3 guest purge mandate");
    List<ChatRequest.ChatMessage> messages =
        List.of(new ChatRequest.ChatMessage("system", workflowContext.systemInstructions()));

    Map<String, Object> preferences = mapContextToPreferences(workflowContext);
    Context context = new Context(userId, workflowContext.contextId(), preferences, Set.of());
    ChatRequest infrastructureRequest = new ChatRequest(userPrompt, messages, Map.of(), context);

    ChatResponse response = aiClient.chat(infrastructureRequest);

    return response != null ? response.content() : "";
  }

  /**
   * Maps {@link WorkflowContext} fields into the Core's {@code Context.preferences} map using the
   * {@code orazaka.pipeline.*} and {@code orazaka.user.*} namespaces.
   */
  private static Map<String, Object> mapContextToPreferences(WorkflowContext workflowContext) {
    Map<String, Object> preferences = new HashMap<>();

    // Pipeline-level directives
    preferences.put("orazaka.pipeline.contextId", workflowContext.contextId());
    preferences.put("orazaka.pipeline.forcedInterceptors", workflowContext.forcedInterceptors());
    preferences.put("orazaka.pipeline.skippedInterceptors", workflowContext.skippedInterceptors());

    // User-level attributes
    preferences.put("orazaka.user.tier", workflowContext.userTier());

    // Flatten business metadata into the orazaka.user.meta namespace
    workflowContext
        .metadata()
        .forEach((key, value) -> preferences.put("orazaka.user.meta." + key, value));

    return Map.copyOf(preferences);
  }
}
