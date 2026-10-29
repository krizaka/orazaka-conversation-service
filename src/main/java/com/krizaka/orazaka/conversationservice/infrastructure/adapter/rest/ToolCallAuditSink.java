package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.core.domain.event.ToolExecutedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Stores every tool the engine ran in the audit log (orazaka-ai-engine#169): who asked, the feature
 * key, the tool, how long the turn had taken and the outcome. Never a prompt, an argument or a
 * result: the event carries none, and the conversation and call ids are not stored. A store that
 * fails never fails the turn.
 */
@Component
class ToolCallAuditSink {

  private static final Logger logger = LoggerFactory.getLogger(ToolCallAuditSink.class);
  private static final String ENGINE = "engine";
  private static final int MAX_ACTION = 160;

  private final OperatorAuditStore store;

  ToolCallAuditSink(OperatorAuditStore store) {
    this.store = store;
  }

  /**
   * Appends the audit line of a tool run.
   *
   * @param event the tool-run event published by the engine
   */
  @EventListener
  void onToolExecuted(ToolExecutedEvent event) {
    String action = "tool-call " + event.toolName();
    try {
      store.append(
          new OperatorAuditStore.Line(
              event.userId(),
              event.userId() == null ? ENGINE : event.userId(),
              action.length() > MAX_ACTION ? action.substring(0, MAX_ACTION) : action,
              event.featureKey(),
              MDC.get("requestId"),
              event.outcome(),
              event.durationMs()));
    } catch (RuntimeException failed) {
      logger.warn("The audit line of tool {} could not be written", event.toolName(), failed);
    }
  }
}
