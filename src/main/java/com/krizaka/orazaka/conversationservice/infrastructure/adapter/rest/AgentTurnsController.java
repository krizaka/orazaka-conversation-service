package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.domain.port.AgentTurnDoor;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.AgentTurnWire;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnRequest;
import com.krizaka.orazaka.core.domain.model.chat.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The door of the Studio service to the engine for one model turn of a client-run agent (ADR-093
 * §4). {@code /internal/v1/**} is reserved to the {@code SERVICE} authority by the security
 * baseline; the user the run acts for is named in the body and checked by the {@link
 * AgentTurnDoor}. Refusals are a {@code status} in the body (the caller reads the server's own
 * words).
 */
@RestController
@RequestMapping("/internal/v1/agent-turns")
public class AgentTurnsController {

  private final AgentTurnDoor door;

  /**
   * @param door the turn door
   */
  public AgentTurnsController(AgentTurnDoor door) {
    this.door = Objects.requireNonNull(door, "AgentTurnDoor must not be null");
  }

  /**
   * Runs one model turn.
   *
   * @param request the conversation, the client's tools and the actor
   * @return the text, the screened tool calls and the usage; 400 {@code invalid_turn} for a
   *     malformed body, 404 {@code actor_unknown}, 403 {@code actor_disabled} or {@code
   *     access_denied}
   */
  @PostMapping
  public ResponseEntity<@NotNull Object> turn(@RequestBody AgentTurnWire.Request request) {
    if (request == null
        || blank(request.actorId())
        || blank(request.featureKey())
        || blank(request.runId())) {
      return refusal(400, "invalid_turn");
    }
    try {
      return ResponseEntity.ok(
          AgentTurnWire.Response.of(
              door.turn(
                  request.actorId(),
                  request.runId(),
                  request.featureKey(),
                  request.system(),
                  messages(request),
                  orEmpty(request.tools()).stream().map(AgentTurnWire.Tool::toDomain).toList())));
    } catch (AgentTurnDoor.Refused refused) {
      return refusal(refused.http(), refused.status());
    }
  }

  private static List<AgentTurnRequest.Message> messages(AgentTurnWire.Request request) {
    return orEmpty(request.messages()).stream()
        .map(
            message ->
                new AgentTurnRequest.Message(
                    message.role(),
                    message.content(),
                    orEmpty(message.calls()).stream()
                        .map(call -> new ToolCall(call.id(), call.name(), call.argumentsJson()))
                        .toList(),
                    message.toolCallId()))
        .toList();
  }

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? List.of() : list;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static ResponseEntity<Object> refusal(int http, String status) {
    return ResponseEntity.status(http).body(Map.of("status", status));
  }
}
