package com.krizaka.orazaka.conversationservice.domain.port;

import com.krizaka.orazaka.core.domain.model.chat.AgentTurnRequest;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnResult;
import com.krizaka.orazaka.core.domain.model.chat.ClientTool;
import java.util.List;

/**
 * One model turn of a client-run agent for the Studio service (ADR-093 §4). The caller names the
 * user the run acts for; the door hydrates that user, builds the engine's context and refuses what
 * the engine must never see: an unknown or disabled actor.
 */
public interface AgentTurnDoor {

  /** A turn the door refused, with the status the caller reads in the body. */
  final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String status;
    private final int http;

    /**
     * @param status the machine-readable refusal, for example {@code actor_disabled}
     * @param http the HTTP status that carries it
     */
    public Refused(String status, int http) {
      super(status);
      this.status = status;
      this.http = http;
    }

    /** The machine-readable refusal. */
    public String status() {
      return status;
    }

    /** The HTTP status that carries it. */
    public int http() {
      return http;
    }
  }

  /**
   * @param actorId the user the run acts for
   * @param runId the Studio run, used as the conversation of the turn
   * @param featureKey the routing key of the turn
   * @param system the system prompt
   * @param messages the conversation so far
   * @param tools the tools the client can run
   * @return the text and the screened tool calls
   * @throws Refused for an unknown or disabled actor, or when the engine refuses the actor
   */
  AgentTurnResult turn(
      String actorId,
      String runId,
      String featureKey,
      String system,
      List<AgentTurnRequest.Message> messages,
      List<ClientTool> tools);
}
