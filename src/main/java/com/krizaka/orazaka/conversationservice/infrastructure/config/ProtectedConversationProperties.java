package com.krizaka.orazaka.conversationservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long the memory of a regulated pack's turns is kept ({@code orazaka.conversation.protected}),
 * orazaka#301. Must equal the studio service's {@code retention.sensitive-run-days}: the consent
 * statement states one figure.
 *
 * @param regulatedDays days from a conversation's last turn
 */
@ConfigurationProperties(prefix = "orazaka.conversation.retention")
public record ProtectedConversationProperties(@DefaultValue("30") int regulatedDays) {

  /** Compact canonical constructor enforcing the wiring's invariants (ERR-106). */
  public ProtectedConversationProperties {
    if (regulatedDays < 1) {
      throw new IllegalArgumentException("regulated-days must be at least 1");
    }
  }
}
