package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.orazaka.assets.domain.port.AssetKeys;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.config.RouterProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.support.PathResolver;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.krizaka.web.problem.ConflictException;
import com.krizaka.web.problem.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deletes what a user generated (orazaka-web-client#86): the files leave every store, then the job
 * row becomes a tombstone ({@code DELETED}, result cleared, prompt and facts kept).
 *
 * <p>Order matters: files first, row second. A store that refuses leaves the row as it was, so the
 * same call can simply be made again; the opposite order would leave files nobody can find to
 * delete. A job that is not finished is refused — cancel it first, so no worker writes into a
 * folder that no longer exists. Another user's job and an unknown job are the same 404.
 */
@Service
public class JobDeletionService {

  private static final Logger logger = LoggerFactory.getLogger(JobDeletionService.class);

  private final JobPersistenceProvider jobs;
  private final RouterProperties router;
  private final DurableAssetStore durableStore;
  private final MediaStorageProperties storage;

  public JobDeletionService(
      JobPersistenceProvider jobs,
      RouterProperties router,
      DurableAssetStore durableStore,
      MediaStorageProperties storage) {
    this.jobs = Objects.requireNonNull(jobs, "JobPersistenceProvider required");
    this.router = Objects.requireNonNull(router, "RouterProperties required");
    this.durableStore = Objects.requireNonNull(durableStore, "DurableAssetStore required");
    this.storage = Objects.requireNonNull(storage, "MediaStorageProperties required");
  }

  /**
   * Deletes a finished job's files and marks it deleted.
   *
   * @param jobId the job
   * @param actorId the caller
   * @param admin whether the caller holds the admin role (deleting for a user, audit-logged)
   * @throws NotFoundException when the job is unknown or not the caller's
   * @throws ConflictException when the job has not finished
   * @throws IllegalStateException when a store refused; nothing was marked, the call can be retried
   */
  public void delete(String jobId, String actorId, boolean admin) {
    JobDto job =
        jobs.getJob(jobId)
            .filter(j -> admin || Objects.equals(j.userId(), actorId))
            .orElseThrow(() -> new NotFoundException("job-not-found", "No job " + jobId + "."));
    if ("DELETED".equals(job.status())) {
      return;
    }
    if ("PENDING".equals(job.status()) || "PROCESSING".equals(job.status())) {
      throw new ConflictException(
          "job-not-finished", "Job " + jobId + " is still running: cancel it before deleting.");
    }
    String owner = job.userId();
    try {
      removeLocal(owner, jobId);
      if (durableStore.enabled()) {
        durableStore.delete(AssetKeys.jobPrefix(storage.tenant(), owner, jobId));
      }
    } catch (IOException | UncheckedIOException e) {
      throw new IllegalStateException("could not delete the files of job " + jobId, e);
    }
    jobs.updateJobStatus(jobId, "DELETED", Map.of(), null);
    logger.info(
        "Job {} of {} deleted by {}{}",
        jobId,
        owner,
        actorId,
        admin && !owner.equals(actorId) ? " (admin)" : "");
  }

  private void removeLocal(String owner, String jobId) throws IOException {
    Path root = PathResolver.resolve(router.uploads().directory()).toAbsolutePath().normalize();
    Path dir = root.resolve(owner).resolve(jobId).normalize();
    if (!dir.startsWith(root) || dir.equals(root) || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
