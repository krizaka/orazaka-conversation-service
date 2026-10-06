package com.orazaka.conversationservice.application.service;

import com.orazaka.persistence.domain.event.JobStatusChangedEvent;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Service managing client Server-Sent Events (SSE) connections for real-time job lifecycle updates.
 * Registers client emitters and dispatches updates when notified via local spring
 * ApplicationEvents.
 */
@Service
public class JobStreamService {

  private static final Logger logger = LoggerFactory.getLogger(JobStreamService.class);

  private final JobPersistenceProvider jobPersistenceProvider;

  // Map of userId -> list of active emitters
  private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public JobStreamService(JobPersistenceProvider jobPersistenceProvider) {
    this.jobPersistenceProvider = jobPersistenceProvider;
  }

  @Scheduled(fixedRate = 15000)
  public void sendHeartbeats() {
    for (var entry : emitters.entrySet()) {
      var userEmitters = entry.getValue();
      for (SseEmitter emitter : userEmitters) {
        try {
          emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException e) {
          logger.debug(
              "Failed to send keep-alive heartbeat to user emitter (IOException), evicting emitter quietly: {}",
              e.getMessage());
          userEmitters.remove(emitter);
        } catch (Exception e) {
          logger.debug(
              "Failed to send keep-alive heartbeat to user emitter, evicting emitter quietly: {}",
              e.getMessage());
          userEmitters.remove(emitter);
        }
      }
    }
  }

  /**
   * Registers a client emitter for real-time job status streaming.
   *
   * @param userId The authenticated user ID.
   * @return A new SseEmitter instance.
   */
  public SseEmitter register(String userId) {
    // 30 minute timeout
    SseEmitter emitter = new SseEmitter(1800000L);

    var userEmitters = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
    userEmitters.add(emitter);

    emitter.onCompletion(() -> userEmitters.remove(emitter));
    emitter.onTimeout(() -> userEmitters.remove(emitter));
    emitter.onError(ex -> userEmitters.remove(emitter));

    try {
      emitter.send(SseEmitter.event().name("connected").data("connection established"));
    } catch (IOException e) {
      logger.debug("Failed to send initial SSE connection established event", e);
      userEmitters.remove(emitter);
    }

    return emitter;
  }

  /**
   * Listens to local job status change events and broadcasts them to connected user emitters.
   *
   * @param event The job status changed event.
   */
  @EventListener
  public void handleJobStatusChanged(JobStatusChangedEvent event) {
    String userId = event.job().userId();
    var userEmitters = emitters.get(userId);
    if (userEmitters == null || userEmitters.isEmpty()) {
      return;
    }

    logger.debug("Broadcasting job state change to user {}: Job ID={}", userId, event.job().id());

    for (SseEmitter emitter : userEmitters) {
      try {
        emitter.send(SseEmitter.event().name("job-status").data(event.job()));
      } catch (IOException e) {
        logger.debug(
            "Failed to broadcast job status change event to user emitter (IOException), evicting emitter quietly: {}",
            e.getMessage());
        userEmitters.remove(emitter);
      } catch (Exception e) {
        logger.debug(
            "Failed to broadcast job status change event to user emitter, evicting emitter quietly: {}",
            e.getMessage());
        userEmitters.remove(emitter);
      }
    }
  }

  /**
   * Broadcasts job progress to the connected user emitters.
   *
   * @param jobId The job ID.
   * @param userId The user ID.
   * @param progress The progress percentage.
   */
  public void broadcastProgress(String jobId, String userId, int progress) {
    var userEmitters = emitters.get(userId);
    if (userEmitters == null || userEmitters.isEmpty()) {
      return;
    }

    logger.debug(
        "Broadcasting job progress to user {}: Job ID={}, Progress={}%", userId, jobId, progress);
    Map<String, Object> payload = Map.of("jobId", jobId, "progress", progress);

    for (SseEmitter emitter : userEmitters) {
      try {
        emitter.send(SseEmitter.event().name("job-progress").data(payload));
      } catch (IOException e) {
        logger.debug(
            "Failed to broadcast job progress event to user emitter (IOException), evicting emitter quietly: {}",
            e.getMessage());
        userEmitters.remove(emitter);
      } catch (Exception e) {
        logger.debug(
            "Failed to broadcast job progress event to user emitter, evicting emitter quietly: {}",
            e.getMessage());
        userEmitters.remove(emitter);
      }
    }
  }

  /**
   * Broadcasts a job failure status directly to the connected user emitters.
   *
   * @param jobId The job ID.
   */
  public void broadcastFailure(String jobId) {
    jobPersistenceProvider
        .getJob(jobId)
        .ifPresent(
            job -> {
              String userId = job.userId();
              var userEmitters = emitters.get(userId);
              if (userEmitters == null || userEmitters.isEmpty()) {
                return;
              }
              logger.debug("Broadcasting job failure to user {}: Job ID={}", userId, jobId);
              Map<String, Object> payload =
                  Map.of(
                      "id",
                      jobId,
                      "userId",
                      userId,
                      "featureKey",
                      job.featureKey(),
                      "status",
                      "FAILED",
                      "errorMessage",
                      job.errorMessage() != null ? job.errorMessage() : "Job execution failed");
              for (SseEmitter emitter : userEmitters) {
                try {
                  emitter.send(SseEmitter.event().name("job-status").data(payload));
                } catch (IOException e) {
                  logger.debug("Failed to broadcast job failure quietly: {}", e.getMessage());
                  userEmitters.remove(emitter);
                } catch (Exception e) {
                  logger.debug("Failed to broadcast job failure: {}", e.getMessage());
                  userEmitters.remove(emitter);
                }
              }
            });
  }

  /**
   * Returns the count of active SSE connections across all users.
   *
   * @return Active connections count.
   */
  public int getActiveConnectionCount() {
    return emitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
  }

  /** Closes and purges all active SSE emitters for all users. */
  @jakarta.annotation.PreDestroy
  public void purgeAllEmitters() {
    logger.info("Purging all active SSE emitters...");
    for (var entry : emitters.entrySet()) {
      var userEmitters = entry.getValue();
      for (SseEmitter emitter : userEmitters) {
        try {
          emitter.complete();
        } catch (Exception e) {
          logger.trace("Ignoring error during emitter purge for user {}", entry.getKey(), e);
        }
      }
      userEmitters.clear();
    }
    emitters.clear();
  }

  /**
   * Pushes a wallet change to a user's open streams.
   *
   * <p>Balance is not a job, but it rides the same SSE connection deliberately: a second stream
   * would double the connection count per user for one number, and the browser already holds this
   * one open for the whole session. The event name is what distinguishes them client-side.
   *
   * @param userId the user whose wallet changed
   * @param eventName the SSE event name, e.g. {@code wallet-balance} or {@code wallet-low}
   * @param payload the event body
   */
  public void broadcastWalletEvent(String userId, String eventName, Map<String, Object> payload) {
    var userEmitters = emitters.get(userId);
    if (userEmitters == null || userEmitters.isEmpty()) {
      return;
    }
    logger.debug("Broadcasting {} to user {}", eventName, userId);
    for (SseEmitter emitter : userEmitters) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(payload));
      } catch (Exception e) {
        logger.debug("Evicting wallet-event emitter for user {}: {}", userId, e.getMessage());
        userEmitters.remove(emitter);
      }
    }
  }
}
