package com.orazaka.conversationservice.application.service;

import com.orazaka.assets.application.service.EncryptedAssetService;
import com.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.orazaka.conversationservice.infrastructure.config.RouterProperties;
import com.orazaka.conversationservice.infrastructure.support.PathResolver;
import com.orazaka.persistence.domain.model.JobDto;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Recovers jobs whose result was written to disk but whose terminal status update never reached the
 * database (e.g. the router was restarted mid-flight, or an in-process update was lost).
 *
 * <p>A job in {@code PENDING}/{@code PROCESSING} with an {@code output/result.json} on disk is
 * known to have finished execution — {@link
 * com.orazaka.conversationservice.infrastructure.adapter.amqp.JobListener} writes that file
 * immediately before persisting the terminal status. This service re-derives the terminal status
 * from that file and persists it, which also re-broadcasts the change over SSE.
 */
@Service
public class JobReconciliationService {

  private static final Logger logger = LoggerFactory.getLogger(JobReconciliationService.class);
  private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "PROCESSING");

  private final JobPersistenceProvider jobPersistenceProvider;
  private final ObjectMapper objectMapper;
  private final String uploadDir;
  private final EncryptedAssetService encryptedAssetService;
  private final boolean acceptPlaintext;

  public JobReconciliationService(
      JobPersistenceProvider jobPersistenceProvider,
      ObjectMapper objectMapper,
      RouterProperties routerProperties,
      EncryptedAssetService encryptedAssetService,
      AssetEncryptionProperties encryption) {
    this.jobPersistenceProvider =
        Objects.requireNonNull(jobPersistenceProvider, "JobPersistenceProvider cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.uploadDir = PathResolver.resolveToString(routerProperties.uploads().directory());
    this.encryptedAssetService =
        Objects.requireNonNull(encryptedAssetService, "EncryptedAssetService cannot be null");
    this.acceptPlaintext = encryption.acceptPlaintext();
  }

  /** Recovers any stuck jobs left behind by a previous run as soon as the app is ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void reconcileOnStartup() {
    reconcileStuckJobs();
  }

  /** Periodically recovers jobs whose terminal status update was lost while the app is running. */
  @Scheduled(
      fixedDelayString = "${orazaka.jobs.reconcile-interval-ms:60000}",
      initialDelayString = "${orazaka.jobs.reconcile-initial-delay-ms:30000}")
  public void reconcileScheduled() {
    reconcileStuckJobs();
  }

  private void reconcileStuckJobs() {
    List<JobDto> stuck = jobPersistenceProvider.findJobsByStatuses(ACTIVE_STATUSES);
    if (stuck.isEmpty()) {
      return;
    }
    int recovered = 0;
    for (JobDto job : stuck) {
      if (reconcileJob(job)) {
        recovered++;
      }
    }
    if (recovered > 0) {
      logger.info("Reconciled {} stuck job(s) from on-disk results.", recovered);
    }
  }

  private boolean reconcileJob(JobDto job) {
    if (job.userId() == null || job.userId().isBlank()) {
      return false;
    }
    Path resultPath =
        Paths.get(uploadDir, job.userId(), job.id(), "output", "result.json")
            .toAbsolutePath()
            .normalize();
    if (!Files.isRegularFile(resultPath)) {
      return false;
    }
    try {
      Map<String, Object> result = readResult(resultPath);
      Object error = result.get("error");
      if (error != null) {
        jobPersistenceProvider.updateJobStatus(job.id(), "FAILED", null, String.valueOf(error));
        logger.warn("Recovered stuck job {} as FAILED from on-disk result.", job.id());
      } else {
        jobPersistenceProvider.updateJobStatus(job.id(), "COMPLETED", result, null);
        logger.info("Recovered stuck job {} as COMPLETED from on-disk result.", job.id());
      }
      return true;
    } catch (Exception e) {
      logger.error("Failed to reconcile stuck job {} from {}", job.id(), resultPath, e);
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readResult(Path resultPath) throws IOException {
    // Through the store: a reconciled result is an asset like any other (ADR-054).
    return objectMapper.readValue(
        encryptedAssetService.readAllBytes(resultPath, acceptPlaintext), Map.class);
  }
}
