package com.krizaka.orazaka.conversationservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.conversationservice.application.service.JobStreamService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Relays wallet events to the browser over the SSE stream the client already holds open.
 *
 * <p>The design's rule is that showing the price before the click is not a UI nicety (§12): an
 * unexpected debit on a job the user did not know was expensive is the number-one support ticket in
 * credit products. A balance that only refreshes on navigation is the same failure one step removed
 * — the user acts on a number that was true a minute ago.
 *
 * <p>Push rather than poll, and over the existing job stream rather than a second connection: the
 * browser already keeps one open for the whole session, and a wallet stream beside it would double
 * the connection count per user to carry one integer.
 *
 * <p>Best-effort by construction. A dropped balance update costs a stale banner until the next
 * event or page load, so nothing here retries or dead-letters — treating a cache hint like a
 * financial message would put broker backpressure in front of a display concern.
 */
@Component
class WalletEventListener {

  private static final Logger logger = LoggerFactory.getLogger(WalletEventListener.class);

  private static final String BALANCE_EVENT = "wallet-balance";
  private static final String LOW_BALANCE_EVENT = "wallet-low";

  private final JobStreamService jobStreamService;

  WalletEventListener(JobStreamService jobStreamService) {
    this.jobStreamService =
        Objects.requireNonNull(jobStreamService, "JobStreamService cannot be null");
  }

  /**
   * Relays a manual credit adjustment, so a support goodwill lands visibly rather than silently.
   *
   * @param event the announcement, read tolerantly — this consumer needs three of its fields
   */
  @RabbitListener(queues = "#{walletEventsQueue.name}")
  void onWalletEvent(Map<String, Object> event) {
    Object actorId = event.get("actorId");
    if (!(actorId instanceof String userId) || userId.isBlank()) {
      logger.warn("Wallet event carried no actor — nothing to push");
      return;
    }
    Map<String, Object> payload = new HashMap<>();
    copy(event, payload, "balanceAfter", "amount", "reason", "available", "thresholdPercent");
    jobStreamService.broadcastWalletEvent(userId, resolveEventName(event), payload);
  }

  /**
   * A low-balance warning and a credit grant are different UI affordances — a banner and a toast —
   * so they arrive under different names rather than as one event the client has to classify.
   */
  private static String resolveEventName(Map<String, Object> event) {
    return event.containsKey("thresholdPercent") ? LOW_BALANCE_EVENT : BALANCE_EVENT;
  }

  private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null) {
        target.put(key, value);
      }
    }
  }
}
