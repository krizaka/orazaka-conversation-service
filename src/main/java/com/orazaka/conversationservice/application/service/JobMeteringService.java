package com.orazaka.conversationservice.application.service;

import com.orazaka.billing.domain.exception.InsufficientCreditsException;
import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.CreditHoldCommand;
import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.UnmeteredTurn;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import com.orazaka.billing.domain.port.UnmeteredTurnRepository;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reserves the estimated cost of an asynchronous job before it is published (ADR-033 §6.3).
 *
 * <p>Authorisation must precede execution: once an MLX render starts the cost is already sunk, so
 * the hold is taken on the submitting thread, before the message reaches the outbox. A refusal
 * therefore surfaces as a {@code 402} on the submission itself rather than as a job that fails
 * later for a reason the user cannot act on.
 *
 * <p>The estimate is deliberately left at zero: the billing service prices the request from its own
 * pricebook. A producer that priced itself would be a second, drifting copy of the pricebook.
 */
@Service
public class JobMeteringService {

  private static final Logger logger = LoggerFactory.getLogger(JobMeteringService.class);

  /** Let the billing service price the hold from its pricebook. */
  private static final long PRICED_BY_BILLING = 0L;

  private final CreditAuthorizationClient creditAuthorizationClient;
  private final CapabilityManager capabilityManager;
  private final UnmeteredTurnRepository unmeteredTurns;

  public JobMeteringService(
      CreditAuthorizationClient creditAuthorizationClient,
      CapabilityManager capabilityManager,
      UnmeteredTurnRepository unmeteredTurns) {
    this.creditAuthorizationClient =
        Objects.requireNonNull(
            creditAuthorizationClient, "CreditAuthorizationClient cannot be null");
    this.capabilityManager =
        Objects.requireNonNull(capabilityManager, "CapabilityManager cannot be null");
    this.unmeteredTurns =
        Objects.requireNonNull(unmeteredTurns, "UnmeteredTurnRepository cannot be null");
  }

  /**
   * Authorises a submission and returns the command stamped with its reservation.
   *
   * @param message the job about to be published
   * @return the same command carrying its {@code holdId}, or unchanged when nothing was metered
   * @throws InsufficientCreditsException when the actor cannot cover the estimate under active
   *     enforcement — the caller's transaction rolls back and the job is never queued
   */
  public JobCommand authorize(JobCommand message) {
    Objects.requireNonNull(message, "message must not be null");
    if (message.userId() == null || message.userId().isBlank()) {
      // An unattributable job has no wallet to charge; metering it would invent a subject.
      logger.warn("Job {} has no actor — submitted unmetered", message.jobId());
      return message;
    }

    CreditHoldResponse hold;
    try {
      hold =
          creditAuthorizationClient.hold(
              new CreditHoldCommand(
                  message.userId(),
                  capabilityOf(message.featureKey()),
                  message.resolvedModel(),
                  // Nothing wider exists at submission: a single job is its own correlation, and
                  // the §6.5 roll-up still aggregates on this key when a workflow supplies one.
                  message.jobId(),
                  message.jobId(),
                  PRICED_BY_BILLING));
    } catch (InsufficientCreditsException e) {
      throw e;
    } catch (RuntimeException e) {
      // Fail open. The fail-mode that would decide otherwise is a row in the billing database,
      // which is precisely what is unreachable here, and refusing every submission because the
      // meter is down trades a margin risk for a total outage. "Recorded loudly" used to mean a log
      // line; it now means a durable record in the same transaction as the job, reconcilable once
      // billing is back — the posture the chat gate declares too (ADR-064). If it cannot be written
      // the exception propagates and the submission rolls back with it.
      unmeteredTurns.record(
          new UnmeteredTurn(
              message.userId(),
              capabilityOf(message.featureKey()),
              message.jobId(),
              "billing unreachable: " + e.getClass().getSimpleName(),
              Instant.now()));
      logger.error("Billing unreachable — job {} submitted unmetered", message.jobId(), e);
      return message;
    }

    if (!hold.metered()) {
      return message;
    }
    if (hold.dryRun()) {
      logger.info(
          "Shadow-metered job {}: hold {} would have been refused under enforcement",
          message.jobId(),
          hold.holdId());
    }
    return message.withReservation(hold.holdId(), message.jobId());
  }

  /**
   * Reads what a capability bills as from its registry row.
   *
   * <p>This used to be a {@code contains()} chain over the feature key, whose javadoc claimed it
   * "mirrors the routing-key resolution the publisher already applies, so a new feature is priced
   * the same way it is routed". ADR-037 made that sentence false by turning routing into data and
   * leaving pricing as a heuristic — so the two could, and did, disagree: the chain prices a Studio
   * composition as CHAT. The row now answers both questions (ADR-038).
   *
   * <p>Falls back to {@code CHAT} for a capability with no row, exactly as the chain did for a key
   * it did not recognise. Deliberately a fallback and not a refusal, unlike routing: mispricing a
   * job is a margin error, whereas refusing to meter it would block a submission the platform is
   * otherwise able to serve. Routing has no such safe default, which is why it has none.
   */
  private BillableCapability capabilityOf(String featureKey) {
    if (featureKey == null) {
      return BillableCapability.CHAT;
    }
    return capabilityManager
        .findByFeatureKey(featureKey)
        .map(CapabilityDeclaration::billableCapability)
        .map(JobMeteringService::parse)
        .orElseGet(
            () -> {
              logger.warn(
                  "No capability row for feature key {} — pricing it as {}",
                  featureKey,
                  BillableCapability.CHAT);
              return BillableCapability.CHAT;
            });
  }

  /**
   * A row whose value is not a BillableCapability is a seeding error, not a reason to fail a job.
   */
  private static BillableCapability parse(String billableCapability) {
    try {
      return BillableCapability.valueOf(billableCapability);
    } catch (IllegalArgumentException e) {
      logger.error(
          "billable_capability '{}' is not a BillableCapability — pricing as {}",
          billableCapability,
          BillableCapability.CHAT);
      return BillableCapability.CHAT;
    }
  }
}
