package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.core.domain.model.chat.AgentTurnResult;
import com.krizaka.orazaka.core.domain.model.chat.ClientTool;
import com.krizaka.orazaka.core.domain.model.chat.ToolCall;
import java.util.List;

/**
 * The wire of {@code POST /internal/v1/agent-turns} (ADR-093 §4): what the Studio service sends for
 * one model turn of a client-run agent, and what comes back.
 */
public final class AgentTurnWire {

  private AgentTurnWire() {}

  /**
   * @param runId the Studio run the turn belongs to
   * @param actorId the user the run acts for (the service caller has no user of its own)
   * @param featureKey the routing key of the turn (ADR-029)
   * @param system the system prompt
   * @param messages the conversation so far
   * @param tools the tools the client can run
   */
  public record Request(
      String runId,
      String actorId,
      String featureKey,
      String system,
      List<Message> messages,
      List<Tool> tools) {}

  /**
   * @param role {@code user}, {@code assistant} or {@code tool}
   * @param content the text
   * @param calls the tool calls of an assistant message
   * @param toolCallId the call a {@code tool} message answers
   */
  public record Message(String role, String content, List<Call> calls, String toolCallId) {}

  /**
   * @param id the call id
   * @param name the tool name
   * @param argumentsJson the arguments as JSON text
   */
  public record Call(String id, String name, String argumentsJson) {

    static Call of(ToolCall call) {
      return new Call(call.id(), call.name(), call.argumentsJson());
    }
  }

  /**
   * @param name the tool name
   * @param description what it does
   * @param inputSchema its JSON schema as text
   */
  public record Tool(String name, String description, String inputSchema) {

    /** The engine's view of this tool. */
    public ClientTool toDomain() {
      return new ClientTool(name, description, inputSchema);
    }
  }

  /**
   * @param promptTokens tokens read, 0 when the provider did not report
   * @param completionTokens tokens written, 0 when the provider did not report
   * @param creditsMicro credits charged by this endpoint: always 0, the engine meters the turn on
   *     its own path
   */
  public record Usage(long promptTokens, long completionTokens, long creditsMicro) {}

  /**
   * @param text the model's text
   * @param toolCalls the calls it asked for, screened by the engine
   * @param usage the tokens
   */
  public record Response(String text, List<Call> toolCalls, Usage usage) {

    /** The answer of the engine's turn. */
    public static Response of(AgentTurnResult result) {
      var usage = result.usage();
      return new Response(
          result.text(),
          result.toolCalls().stream().map(Call::of).toList(),
          new Usage(Math.max(0, usage.promptTokens()), Math.max(0, usage.completionTokens()), 0));
    }
  }
}
