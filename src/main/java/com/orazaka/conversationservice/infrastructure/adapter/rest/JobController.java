package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.orazaka.conversationservice.application.service.JobQueuePublisherService;
import com.orazaka.conversationservice.application.service.JobStreamService;
import com.orazaka.core.domain.model.job.JobInfo;
import com.orazaka.core.domain.ports.inbound.ChatSessionService;
import com.orazaka.core.domain.ports.inbound.JobService;
import com.orazaka.identity.domain.exception.InvalidRequestException;
import com.orazaka.identity.domain.model.User;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for the {@code jobs} resource: submit, query, and stream jobs, plus automation
 * approval and admin maintenance. Decoupled from persistence per ERR-102.
 *
 * <p>Consolidates the former Job, AdminJob and AutomationJob controllers. Admin maintenance
 * endpoints are guarded with method-level {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

  private static final Logger logger = LoggerFactory.getLogger(JobController.class);

  private final JobService jobService;
  private final JobQueuePublisherService jobQueuePublisher;
  private final JobStreamService jobStreamService;
  private final ChatSessionService chatSessionService;
  private final ObjectProvider<StatefulRedisConnection<String, byte[]>> redisConnectionProvider;

  public JobController(
      JobService jobService,
      JobQueuePublisherService jobQueuePublisher,
      JobStreamService jobStreamService,
      ChatSessionService chatSessionService,
      ObjectProvider<StatefulRedisConnection<String, byte[]>> redisConnectionProvider) {
    this.jobService = Objects.requireNonNull(jobService, "JobService cannot be null");
    this.jobQueuePublisher =
        Objects.requireNonNull(jobQueuePublisher, "JobQueuePublisherService cannot be null");
    this.jobStreamService =
        Objects.requireNonNull(jobStreamService, "JobStreamService cannot be null");
    this.chatSessionService =
        Objects.requireNonNull(chatSessionService, "ChatSessionService cannot be null");
    this.redisConnectionProvider =
        Objects.requireNonNull(redisConnectionProvider, "RedisConnectionProvider cannot be null");
  }

  /**
   * <b>There is no job submission here any more (ADR-068 §5).</b>
   *
   * <p>{@code POST /api/v1/jobs} was the rawest door 1: a feature key and an opaque payload map,
   * straight to the queue. It carried the {@code orazaka.*} key hole M1.7 found, it declared {@code
   * DataClass.STANDARD} for every caller because there was no pack declaration to read, and {@code
   * #32}'s remainder — a {@code filePath} the listener never scoped — lived on it.
   *
   * <p>It was deleted rather than restricted to {@code SERVICE}, because <b>no caller needed
   * it</b>: the run path publishes step commands through the studio outbox, and the end-to-end
   * suites that used it now start runs. A {@code SERVICE}-only variant would have been a door with
   * a narrower doorway and the same absence of controls behind it.
   *
   * <p>{@link JobQueuePublisherService} stays, and is worth being exact about: it is still used by
   * {@link #approveJob}, which releases an automation job a human has just approved. That is not an
   * invocation — the job exists, its payload was written when the automation ran, and approval is
   * the gate in front of it.
   *
   * <p>What remains on this resource is reading: the list, one job, the SSE relay, progress
   * reported by a worker, and the approval pair. None of them starts work.
   */
  /** Retrieves a paginated list of jobs for the authenticated user (or all jobs for admins). */
  @GetMapping
  public ResponseEntity<@NotNull Page<@NotNull JobInfo>> getJobs(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @AuthenticationPrincipal User user) {
    logger.debug("Fetching paginated jobs for user: {}, page: {}, size: {}", user.id(), page, size);

    PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
    Page<@NotNull JobInfo> jobs;
    if (user.authorities().contains("ROLE_ADMIN")) {
      logger.debug("Principal is ADMIN. Bypassing user filter to query all platform jobs.");
      jobs = jobService.getAllJobs(pageable);
    } else {
      jobs = jobService.getJobsByUserId(user.id().toString(), pageable);
    }
    return ResponseEntity.ok(jobs);
  }

  /** Fetches the single, atomic state of a specific job. */
  @GetMapping("/{id}")
  public ResponseEntity<@NotNull JobInfo> getJob(@PathVariable String id) {
    logger.debug("Resolving status for job ID: {}", id);
    return jobService
        .getJob(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Registers a Server-Sent Events stream for real-time job status notifications. */
  @GetMapping("/stream")
  public SseEmitter streamJobs(@AuthenticationPrincipal User user) {
    logger.debug("Registering SSE emitter for user: {}", user.id());
    return jobStreamService.register(user.id().toString());
  }

  /** Request DTO for job progress updates. */
  public static record JobProgressRequest(Integer progress) {
    public JobProgressRequest {
      Objects.requireNonNull(progress, "Progress percentage is required");
      if (progress < 0 || progress > 100) {
        throw new InvalidRequestException("Progress must be between 0 and 100");
      }
    }
  }

  /** Updates progress of a running job and broadcasts it to connected users. */
  @PostMapping("/{id}/progress")
  public ResponseEntity<Void> updateJobProgress(
      @PathVariable String id, @RequestBody JobProgressRequest request) {
    logger.debug("Received progress update for job ID: {}, progress: {}%", id, request.progress());
    jobService
        .getJob(id)
        .ifPresent(job -> jobStreamService.broadcastProgress(id, job.userId(), request.progress()));
    return ResponseEntity.ok().build();
  }

  // ── Automation approval ──────────────────────────────────────────────────

  /** Approves a pending automation job and dispatches its payload to the execution queue. */
  @PostMapping("/{jobId}/approve")
  public ResponseEntity<Map<String, Object>> approveJob(
      @PathVariable String jobId, @RequestHeader("X-User-Id") String userId) {
    if (logger.isInfoEnabled()) {
      logger.info("Approving automation job with id: {}", sanitize(jobId));
    }
    jobQueuePublisher.publishApproval(jobId, userId);
    return ResponseEntity.ok(
        Map.of(
            "jobId", jobId,
            "status", "APPROVED",
            "message", "Job approved and dispatched for execution"));
  }

  /** Revokes a pending automation job, preventing its execution. */
  @PostMapping("/{jobId}/revoke")
  public ResponseEntity<Map<String, Object>> revokeJob(
      @PathVariable String jobId, @RequestHeader("X-User-Id") String userId) {
    if (logger.isInfoEnabled()) {
      logger.info("Revoking automation job with id: {}", sanitize(jobId));
    }
    return ResponseEntity.ok(
        Map.of(
            "jobId", jobId,
            "status", "REVOKED",
            "message", "Job revoked — execution cancelled"));
  }

  // ── Admin maintenance (ROLE_ADMIN) ───────────────────────────────────────

  /** Purges jobs, chat sessions and rate-limit cache for the default test accounts. */
  @PostMapping("/purge")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<Void> purgeTestData() {
    logger.info("Admin initiated database and cache purge of test accounts.");

    List<String> testUserIds =
        List.of(
            "550e8400-e29b-41d4-a716-446655440001", // admin
            "550e8400-e29b-41d4-a716-446655440002" // user
            );

    for (String userId : testUserIds) {
      try {
        jobService.purgeJobsByUserId(userId);
        chatSessionService.purgeSessionsByUserId(userId);
        logger.debug("Successfully purged database jobs and sessions for user ID: {}", userId);
      } catch (DataAccessException e) {
        logger.error("Failed to purge database jobs/sessions for user ID: {}", userId, e);
      }
    }

    redisConnectionProvider.ifAvailable(
        connection -> {
          try {
            var commands = connection.sync();
            commands.flushdb();
            logger.info("Successfully executed FLUSHDB on Redis test database.");
            for (String userId : testUserIds) {
              var keys = commands.keys(userId + "*");
              if (keys != null && !keys.isEmpty()) {
                commands.del(keys.toArray(new String[0]));
                logger.info("Evicted Redis rate-limiting keys for user ID: {}: {}", userId, keys);
              }
            }
          } catch (RuntimeException e) {
            logger.warn("Failed to evict rate-limiting keys/flush from Redis", e);
          }
        });

    try {
      jobStreamService.purgeAllEmitters();
      logger.info("Successfully purged active SSE emitters.");
    } catch (RuntimeException e) {
      logger.error("Failed to purge SSE emitters", e);
    }

    return ResponseEntity.ok().build();
  }

  /** Retrieves the current active SSE connection count. */
  @GetMapping("/active-connections")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<Integer> getActiveConnections() {
    return ResponseEntity.ok(jobStreamService.getActiveConnectionCount());
  }

  /** Strips CR/LF and control characters to prevent log injection. */
  private static String sanitize(String input) {
    if (input == null) {
      return "null";
    }
    return input.replaceAll("[\\r\\n\\t]", "_");
  }
}
