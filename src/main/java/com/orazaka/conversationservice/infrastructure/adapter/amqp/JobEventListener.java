package com.orazaka.conversationservice.infrastructure.adapter.amqp;

import com.orazaka.conversationservice.application.service.JobStreamService;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Relays job lifecycle events ({@code job.{jobId}.progress|done|error}, AGENTS.md §6) from the
 * {@code orazaka.events} exchange to the SSE job streams.
 *
 * <p>Deliberately separated from {@link JobListener} (which executes jobs): this relay is the part
 * of the router that later moves behind the edge gateway as an exclusive-queue-per-node consumer,
 * so it must carry no execution concern.
 */
@Component
public class JobEventListener {

  private static final Logger logger = LoggerFactory.getLogger(JobEventListener.class);

  private final JobPersistenceProvider jobPersistenceProvider;
  private final JobStreamService jobStreamService;

  public JobEventListener(
      JobPersistenceProvider jobPersistenceProvider, JobStreamService jobStreamService) {
    this.jobPersistenceProvider =
        Objects.requireNonNull(jobPersistenceProvider, "JobPersistenceProvider cannot be null");
    this.jobStreamService =
        Objects.requireNonNull(jobStreamService, "JobStreamService cannot be null");
  }

  /**
   * Consumes a job lifecycle event: {@code .progress} relays to the SSE stream; {@code .done} and
   * {@code .error} (emitted by workers that own their execution — the video worker since Phase 3b)
   * apply the terminal status to the job row. Terminal updates are idempotent, so event
   * redeliveries need no dedup ledger.
   *
   * @param message the event payload ({@code jobId} plus progress/result/error)
   * @param routingKey the received routing key ({@code job.{jobId}.progress|done|error})
   */
  @RabbitListener(queues = MessagingContract.EVENTS_JOB_RELAY_QUEUE)
  public void onJobEvent(
      Map<String, Object> message, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    Object jobIdValue = message.get("jobId");
    if (routingKey == null || jobIdValue == null) {
      logger.debug("Ignoring job event without routing key or jobId: {}", routingKey);
      return;
    }
    String jobId = jobIdValue.toString();

    if (routingKey.endsWith(MessagingContract.JOB_EVENT_PROGRESS_SUFFIX)) {
      int progress = message.get("progress") instanceof Number n ? n.intValue() : 0;
      logger.debug("Received AMQP progress update for job ID: {}, progress: {}%", jobId, progress);
      jobPersistenceProvider
          .getJob(jobId)
          .ifPresent(job -> jobStreamService.broadcastProgress(jobId, job.userId(), progress));
      return;
    }

    boolean terminal = routingKey.endsWith(".done") || routingKey.endsWith(".error");
    if (terminal && jobPersistenceProvider.getJob(jobId).isEmpty()) {
      // A protected job's row is purged once it is terminal (ADR-065), and this relay can hear the
      // outcome after the purge. Updating a row that is gone throws, and a thrown event is retried
      // into the DLQ — which would keep the outcome's content in a queue after the job plane
      // deleted it. A Studio step drained by a worker never had a row here either.
      logger.debug("Job {} has no row to update; its outcome is not recorded here", jobId);
      return;
    }

    if (routingKey.endsWith(".done")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> result =
          message.get("result") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
      logger.info("Job {} completed by its owning worker", jobId);
      jobPersistenceProvider.updateJobStatus(jobId, "COMPLETED", result, null);
      return;
    }

    if (routingKey.endsWith(".error")) {
      String error = message.get("error") instanceof String s ? s : "Worker reported failure";
      logger.warn("Job {} failed in its owning worker: {}", jobId, error);
      jobPersistenceProvider.updateJobStatus(jobId, "FAILED", null, error);
      return;
    }

    logger.debug("Ignoring job event with unhandled routing key: {}", routingKey);
  }
}
