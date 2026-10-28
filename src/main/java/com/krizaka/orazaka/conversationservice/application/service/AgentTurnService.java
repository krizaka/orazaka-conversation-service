package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.orazaka.conversationservice.domain.port.AgentTurnDoor;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnRequest;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnResult;
import com.krizaka.orazaka.core.domain.model.chat.ClientTool;
import com.krizaka.orazaka.core.domain.model.security.AccessDeniedException;
import com.krizaka.orazaka.core.domain.ports.inbound.AgentTurns;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link AgentTurnDoor} over the engine's {@link AgentTurns}: the actor is hydrated from the user
 * directory (unknown: {@code actor_unknown}, disabled: {@code actor_disabled}), the context is
 * resolved with the run as conversation, and an engine refusal is only {@code access_denied}, the
 * reason staying in the engine's audit line.
 */
@Service
public class AgentTurnService implements AgentTurnDoor {

  private static final Logger logger = LoggerFactory.getLogger(AgentTurnService.class);

  private final AgentTurns turns;
  private final ContextService contextService;
  private final UserDirectoryClient users;

  /**
   * @param turns the engine's inbound port for a client-run agent's turn
   * @param contextService assembles the actor's context
   * @param users the user directory, to hydrate the actor
   */
  public AgentTurnService(
      AgentTurns turns, ContextService contextService, UserDirectoryClient users) {
    this.turns = Objects.requireNonNull(turns, "AgentTurns must not be null");
    this.contextService = Objects.requireNonNull(contextService, "ContextService must not be null");
    this.users = Objects.requireNonNull(users, "UserDirectoryClient must not be null");
  }

  @Override
  public AgentTurnResult turn(
      String actorId,
      String runId,
      String featureKey,
      String system,
      List<AgentTurnRequest.Message> messages,
      List<ClientTool> tools) {
    User actor = hydrate(actorId);
    if (!actor.enabled()) {
      throw new Refused("actor_disabled", 403);
    }
    Context context = contextService.resolve(actor, runId);
    try {
      return turns.turn(new AgentTurnRequest(featureKey, system, messages, tools, context));
    } catch (AccessDeniedException denied) {
      // The reason is in the engine's audit line; the answer says only that it is refused.
      logger.info("Agent turn refused for run {}", runId);
      throw new Refused("access_denied", 403);
    }
  }

  private User hydrate(String actorId) {
    try {
      User actor = users.getUser(actorId);
      if (actor != null) {
        return actor;
      }
    } catch (RuntimeException unknown) {
      // Fall through: whatever the directory could not give is an actor we do not know.
    }
    throw new Refused("actor_unknown", 404);
  }
}
