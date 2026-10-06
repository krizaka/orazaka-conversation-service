package com.orazaka.conversationservice.application.service;

import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.persistence.domain.model.OutboxMessage;
import com.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import com.orazaka.persistence.domain.ports.inbound.OutboxStore;
import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a capability row and announces that it changed.
 *
 * <p>The announcement is the point. Producers cache a capability's route on the dispatch hot path,
 * and before this event their only staleness bound was a TTL — so disabling a capability left it
 * dispatchable for up to a minute (ADR-037's reported gap, closed here). The event turns the TTL
 * into a floor rather than the mechanism.
 *
 * <p>Published through the transactional outbox, in the same transaction as the write (AGENTS.md
 * §6). A dual write would let the row change without the announcement — which is precisely the
 * stale-cache bug this exists to prevent, reintroduced one layer down.
 */
@Service
public class CapabilityConfigService {

  private static final String CAPABILITY_AGGREGATE = "capability";

  private final CapabilityManager capabilityManager;
  private final OutboxStore outboxStore;

  public CapabilityConfigService(CapabilityManager capabilityManager, OutboxStore outboxStore) {
    this.capabilityManager =
        Objects.requireNonNull(capabilityManager, "CapabilityManager cannot be null");
    this.outboxStore = Objects.requireNonNull(outboxStore, "OutboxStore cannot be null");
  }

  /**
   * Saves a capability and announces the change.
   *
   * @param capability the row to write
   * @return the stored state
   */
  @Transactional
  public CapabilityDeclaration save(CapabilityDeclaration capability) {
    CapabilityDeclaration saved = capabilityManager.save(capability);
    outboxStore.append(
        new OutboxMessage(
            CAPABILITY_AGGREGATE,
            saved.featureKey(),
            MessagingContract.EVENTS_EXCHANGE,
            MessagingContract.EVT_CAPABILITY_CHANGED,
            Map.of("featureKey", saved.featureKey(), "enabled", saved.enabled())));
    return saved;
  }
}
