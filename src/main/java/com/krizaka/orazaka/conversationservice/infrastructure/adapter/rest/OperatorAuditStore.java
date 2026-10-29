package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import java.time.Instant;
import java.util.List;

/** Where the operator audit lines of the conversation service are written and read (ADR-085). */
public interface OperatorAuditStore {

  /**
   * One audited action: who, what, which target, in which request, how it ended and, for a tool
   * run, how long it took ({@code null} for an operator write).
   */
  record Line(
      String actorId,
      String actorLabel,
      String action,
      String targetId,
      String requestId,
      String outcome,
      Long durationMs) {

    /** An operator write: no duration. */
    public Line(
        String actorId,
        String actorLabel,
        String action,
        String targetId,
        String requestId,
        String outcome) {
      this(actorId, actorLabel, action, targetId, requestId, outcome, null);
    }
  }

  /** A stored line. */
  record Stored(
      long id,
      Instant at,
      String actor,
      String action,
      String targetId,
      String requestId,
      String outcome,
      Long durationMs) {}

  void append(Line line);

  /** The newest lines first. */
  List<Stored> recent(int limit);
}
