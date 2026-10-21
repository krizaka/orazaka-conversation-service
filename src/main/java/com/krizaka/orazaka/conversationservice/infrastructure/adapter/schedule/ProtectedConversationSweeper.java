package com.krizaka.orazaka.conversationservice.infrastructure.adapter.schedule;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore.Erased;
import com.krizaka.orazaka.conversationservice.infrastructure.config.ProtectedConversationProperties;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ages out the conversation memory of regulated packs, daily: the 30 days the consent statement
 * states, counted from the conversation's last turn (orazaka#301). The window is a setting, not a
 * constant - it must equal the studio service's {@code retention.sensitive-run-days}.
 *
 * <p>Counts are logged, never content, and never who.
 */
@Component
public class ProtectedConversationSweeper {

  private static final Logger logger = LoggerFactory.getLogger(ProtectedConversationSweeper.class);

  private final ProtectedConversationStore store;
  private final ProtectedConversationProperties properties;

  public ProtectedConversationSweeper(
      ProtectedConversationStore store, ProtectedConversationProperties properties) {
    this.store = Objects.requireNonNull(store, "ProtectedConversationStore required");
    this.properties = Objects.requireNonNull(properties, "properties required");
  }

  /** Removes the conversations that left the window. */
  @Scheduled(cron = "${orazaka.router.regulated-retention.cron:0 45 3 * * *}")
  public void sweep() {
    Erased erased = store.eraseOlderThan(properties.regulatedDays());
    if (erased.conversations() > 0) {
      logger.info(
          "Retention removed {} conversation(s), {} message(s) of a regulated pack",
          erased.conversations(),
          erased.messages());
    }
  }
}
