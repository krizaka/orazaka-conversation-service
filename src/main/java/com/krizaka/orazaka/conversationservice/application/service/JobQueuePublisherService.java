package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.orazaka.jobs.domain.exception.UnroutableCapabilityException;
import com.krizaka.orazaka.jobs.domain.model.CapabilityRoute;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.krizaka.orazaka.persistence.domain.model.OutboxMessage;
import com.krizaka.orazaka.persistence.domain.ports.inbound.OutboxStore;
import com.krizaka.orazaka.persistence.infrastructure.config.MessagingContract;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Service component responsible for publishing jobs through the transactional outbox (AGENTS.md
 * §6): the message is committed with the caller's transaction and delivered to RabbitMQ by the
 * relay, so a broker outage defers delivery instead of failing the submission.
 *
 * <p>It is also where every asynchronous submission is metered. This is the one door to {@code
 * orazaka.jobs} — four controllers and two services reach the broker through it — so taking the
 * hold here is what makes "no compute starts without a hold" (ADR-033 §6) a property of the
 * topology rather than a convention six call sites have to remember.
 */
@Service
public class JobQueuePublisherService {

  private static final Logger logger = LoggerFactory.getLogger(JobQueuePublisherService.class);

  private static final String JOB_AGGREGATE = "job";

  private final OutboxStore outboxStore;
  private final JdbcClient jdbcClient;
  private final UserDirectoryClient userDirectoryService;
  private final JobMeteringService jobMeteringService;
  private final CapabilityRoutingClient capabilityRoutingClient;

  public JobQueuePublisherService(
      OutboxStore outboxStore,
      JdbcClient jdbcClient,
      UserDirectoryClient userDirectoryService,
      JobMeteringService jobMeteringService,
      CapabilityRoutingClient capabilityRoutingClient) {
    this.outboxStore = Objects.requireNonNull(outboxStore, "OutboxStore cannot be null");
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "JdbcClient cannot be null");
    this.userDirectoryService =
        Objects.requireNonNull(userDirectoryService, "UserDirectoryClient cannot be null");
    this.jobMeteringService =
        Objects.requireNonNull(jobMeteringService, "JobMeteringService cannot be null");
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "CapabilityRoutingClient cannot be null");
  }

  /**
   * Reserves the job's estimated cost, then appends it to the outbox for asynchronous delivery to
   * {@code orazaka.jobs}.
   *
   * <p>The hold is taken before the append and inside the caller's transaction. A submission that
   * rolls back after the hold leaves a reservation with no job behind it — released by the hold
   * sweeper at TTL, which is the lesser of the two failure modes: the alternative is queueing work
   * that was never authorised.
   *
   * @param message the job message to publish
   * @throws com.krizaka.billing.domain.exception.InsufficientCreditsException when the actor cannot
   *     cover the estimate under active enforcement
   */
  public void publish(JobCommand message) {
    JobCommand metered = jobMeteringService.authorize(message);
    // A hold was taken here, so the pipeline that runs this job downstream must not take a second
    // one. Measured before the marker existed: one async chat job opened two CHAT holds and
    // debited two credits for one inference — this service's, and the interceptor's, which stamps
    // no jobId because it believes no async job is behind it (ADR-045).
    //
    // Conditional on holdId, not unconditional: authorize() returns the command unchanged when
    // billing is off or unreachable, and claiming to meter something nobody metered would leave
    // the work free rather than double-charged. The marker means "a hold exists", so it is set
    // exactly when one does.
    JobCommand authorized = metered.holdId() != null ? metered.withDeferredMetering() : metered;
    logger.debug("Appending job message to the outbox: {}", authorized.jobId());
    String routingKey = resolveRoutingKey(authorized);
    outboxStore.append(
        new OutboxMessage(
            JOB_AGGREGATE,
            authorized.jobId(),
            MessagingContract.JOBS_EXCHANGE,
            routingKey,
            authorized));
  }

  private String resolveRoutingKey(JobCommand message) {
    String featureKey = message.featureKey();
    String userId = message.userId();

    if (userId != null && featureKey != null) {
      try {
        // The user's tier is identity-owned data resolved via the (cached) directory —
        // orazaka_routing_rules is config-plane data local to this database. A tier rule is a
        // deliberate per-tier override and still wins over the capability's default route.
        String tier = userDirectoryService.getUser(userId).rateLimitTier();
        Optional<String> tierOverride =
            jdbcClient
                .sql(
                    "SELECT target_routing_key FROM orazaka_routing_rules "
                        + "WHERE feature_key = ? AND user_tier = ? AND is_active = true "
                        + "LIMIT 1")
                .param(1, featureKey)
                .param(2, tier)
                .query(String.class)
                .optional();
        if (tierOverride.isPresent()) {
          return tierOverride.get();
        }
      } catch (Exception e) {
        logger.warn(
            "Failed to resolve a tier routing override; falling back to the capability row", e);
      }
    }
    return capabilityRoute(featureKey);
  }

  /**
   * The capability's own route — read, never derived.
   *
   * <p>This replaces a second {@code contains()} chain that lived here, which had already diverged
   * from the studio service's copy before phase A deleted that one: it never grew a {@code compose}
   * branch. Both are now the same lookup against the same row (ADR-038).
   *
   * <p><b>No default.</b> A capability with no enabled route is refused at submission rather than
   * published to the text queue, where a worker that cannot execute it would fail it — or a
   * language model would answer it. That is the whole of ADR-037, applied to the second producer.
   */
  private String capabilityRoute(String featureKey) {
    return capabilityRoutingClient
        .route(featureKey)
        .map(CapabilityRoute::routingKey)
        .orElseThrow(
            () -> new UnroutableCapabilityException(featureKey, "asynchronous submission"));
  }

  /**
   * Appends an approval event for an automation job to the outbox.
   *
   * @param jobId The UUID of the approved job.
   * @param userId The user who approved the job.
   */
  public void publishApproval(String jobId, String userId) {
    if (logger.isInfoEnabled()) {
      logger.info("Publishing automation approval for job {}.", sanitize(jobId));
    }
    var approvalMessage =
        Map.of(
            "jobId", jobId,
            "userId", userId,
            "action", "APPROVED");
    outboxStore.append(
        new OutboxMessage(
            JOB_AGGREGATE,
            jobId,
            MessagingContract.JOBS_EXCHANGE,
            MessagingContract.JOB_AUTOMATION_APPROVED,
            approvalMessage));
  }

  /** Strips CR/LF and control characters to prevent log injection. */
  private static String sanitize(String input) {
    if (input == null) return "null";
    return input.replaceAll("[\\r\\n\\t]", "_");
  }
}
