package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.application.service.ContextService;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.AgentTurnWire;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnRequest;
import com.krizaka.orazaka.core.domain.model.chat.ToolCall;
import com.krizaka.orazaka.core.domain.model.security.AccessDeniedException;
import com.krizaka.orazaka.core.domain.ports.inbound.AgentTurns;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The door of the Studio service to the engine for one model turn of a client-run agent (ADR-093
 * §4). {@code /internal/v1/**} is reserved to the {@code SERVICE} authority by the security
 * baseline; the user the run acts for is named in the body, hydrated here and refused when unknown
 * or disabled, so the engine sees a verified {@link Context} exactly as for a user's own turn.
 * Refusals are a {@code status} in the body (the caller reads the server's own words).
 */
@RestController
@RequestMapping("/internal/v1/agent-turns")
public class AgentTurnsController {

  private static final Logger logger = LoggerFactory.getLogger(AgentTurnsController.class);

  private final AgentTurns turns;
  private final ContextService contextService;
  private final UserDirectoryClient users;

  /**
   * @param turns the engine's inbound port for a client-run agent's turn
   * @param contextService assembles the actor's context
   * @param users the user directory, to hydrate the actor
   */
  public AgentTurnsController(
      AgentTurns turns, ContextService contextService, UserDirectoryClient users) {
    this.turns = Objects.requireNonNull(turns, "AgentTurns must not be null");
    this.contextService = Objects.requireNonNull(contextService, "ContextService must not be null");
    this.users = Objects.requireNonNull(users, "UserDirectoryClient must not be null");
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
    User actor;
    try {
      actor = users.getUser(request.actorId());
    } catch (RuntimeException unknown) {
      return refusal(404, "actor_unknown");
    }
    if (actor == null) {
      return refusal(404, "actor_unknown");
    }
    if (!actor.enabled()) {
      return refusal(403, "actor_disabled");
    }
    Context context = contextService.resolve(actor, request.runId());
    try {
      return ResponseEntity.ok(AgentTurnWire.Response.of(turns.turn(toDomain(request, context))));
    } catch (AccessDeniedException denied) {
      // The reason is in the engine's audit line; the answer says only that it is refused.
      logger.info("Agent turn refused for run {}", request.runId());
      return refusal(403, "access_denied");
    }
  }

  private static AgentTurnRequest toDomain(AgentTurnWire.Request request, Context context) {
    List<AgentTurnRequest.Message> messages =
        orEmpty(request.messages()).stream()
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
    return new AgentTurnRequest(
        request.featureKey(),
        request.system(),
        messages,
        orEmpty(request.tools()).stream().map(AgentTurnWire.Tool::toDomain).toList(),
        context);
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
