package com.krizaka.orazaka.conversationservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.conversationservice.application.service.JobStreamService;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobEventListenerTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Mock private JobPersistenceProvider jobPersistenceProvider;

  @Mock private JobStreamService jobStreamService;

  private JobEventListener listener;

  @BeforeEach
  void setUp() {
    listener = new JobEventListener(jobPersistenceProvider, jobStreamService);
  }

  @Test
  void constructor_nullDependencies_throws() {
    assertThrows(NullPointerException.class, () -> new JobEventListener(null, jobStreamService));
    assertThrows(
        NullPointerException.class, () -> new JobEventListener(jobPersistenceProvider, null));
  }

  @Test
  @DisplayName("A progress event is relayed to the SSE stream")
  void onJobEvent_progressKey_callsStreamService() {
    JobDto job =
        new JobDto(
            "job-123",
            "user-456",
            "feature-x",
            "RUNNING",
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.of(job));

    listener.onJobEvent(Map.of("jobId", "job-123", "progress", 45), "job.job-123.progress");

    verify(jobStreamService).broadcastProgress("job-123", "user-456", 45, Map.of());
  }

  @Test
  @DisplayName("A done event applies the COMPLETED status with the worker's result")
  void onJobEvent_doneKey_completesJob() {
    Map<String, Object> result = Map.of("url", "/uploads/u/j/output/video.mp4", "format", "mp4");
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.of(job("job-123")));

    listener.onJobEvent(Map.of("jobId", "job-123", "result", result), "job.job-123.done");

    verify(jobPersistenceProvider).updateJobStatus("job-123", "COMPLETED", result, null);
    verifyNoInteractions(jobStreamService);
  }

  @Test
  @DisplayName("An error event applies the FAILED status with the worker's reason")
  void onJobEvent_errorKey_failsJob() {
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.of(job("job-123")));
    listener.onJobEvent(Map.of("jobId", "job-123", "error", "prompt missing"), "job.job-123.error");

    verify(jobPersistenceProvider).updateJobStatus("job-123", "FAILED", null, "prompt missing");
    verifyNoInteractions(jobStreamService);
  }

  @Test
  @DisplayName("An outcome for a job whose row was purged is dropped, not thrown into the DLQ")
  void onJobEvent_terminalForAPurgedJob_isDropped() {
    when(jobPersistenceProvider.getJob("job-purged")).thenReturn(Optional.empty());

    listener.onJobEvent(
        Map.of("jobId", "job-purged", "result", Map.of("content", "x")), "job.job-purged.done");
    listener.onJobEvent(Map.of("jobId", "job-purged", "error", "refused"), "job.job-purged.error");

    verify(jobPersistenceProvider, org.mockito.Mockito.never())
        .updateJobStatus(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private static JobDto job(String id) {
    return new JobDto(
        id,
        "user-456",
        "feature-x",
        "PROCESSING",
        Map.of(),
        Map.of(),
        null,
        Instant.now(FIXED_CLOCK),
        Instant.now(FIXED_CLOCK));
  }

  @Test
  @DisplayName("Events without a routing key or jobId are ignored")
  void onJobEvent_missingKeyOrJobId_isIgnored() {
    listener.onJobEvent(Map.of("jobId", "job-123", "progress", 10), null);
    listener.onJobEvent(Map.of("progress", 10), "job.job-123.progress");

    verifyNoInteractions(jobPersistenceProvider, jobStreamService);
  }

  @Test
  @DisplayName("A progress event carries the phase and the step n of N to the stream")
  void onJobEvent_progressWithPhase_relaysThePhase() {
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.of(job("job-123")));

    listener.onJobEvent(
        Map.of("jobId", "job-123", "progress", 40, "phase", "generating", "step", 4, "steps", 10),
        "job.job-123.progress");

    verify(jobStreamService)
        .broadcastProgress(
            "job-123",
            job("job-123").userId(),
            40,
            Map.of("phase", "generating", "step", 4, "steps", 10));
  }

  @Test
  @DisplayName("An unknown phase or a nonsense step is dropped, not relayed")
  void progressDetail_dropsWhatItDoesNotKnow() {
    assertEquals(
        Map.of(),
        JobEventListener.progressDetail(Map.of("phase", "daydreaming", "step", 3, "steps", 0)));
  }

  @Test
  @DisplayName("The stage, the real weights percentage, the ETA and the timestamp reach the stream")
  void progressDetail_relaysTheStage() {
    assertEquals(
        Map.of(
            "phase",
            "loading",
            "stage",
            "STREAMING_WEIGHTS_NVME",
            "weightsPercent",
            40,
            "etaSeconds",
            7,
            "at",
            1700000000000L),
        JobEventListener.progressDetail(
            Map.of(
                "phase",
                "loading",
                "stage",
                "STREAMING_WEIGHTS_NVME",
                "weightsPercent",
                40,
                "etaSeconds",
                7,
                "at",
                1700000000000L)));
  }

  @Test
  @DisplayName("An unknown stage, a percentage over 100 or a negative ETA is dropped")
  void progressDetail_dropsABadStage() {
    assertEquals(
        Map.of(),
        JobEventListener.progressDetail(
            Map.of("stage", "DAYDREAMING", "weightsPercent", 40, "etaSeconds", 7)));
    assertEquals(
        Map.of("stage", "COMPLETED"),
        JobEventListener.progressDetail(
            Map.of("stage", "COMPLETED", "weightsPercent", 140, "etaSeconds", -1)));
  }

  @Test
  @DisplayName("A cancelled event applies CANCELLED, never FAILED")
  void onJobEvent_cancelledKey_cancelsJob() {
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.of(job("job-123")));

    listener.onJobEvent(Map.of("jobId", "job-123"), "job.job-123.cancelled");

    verify(jobPersistenceProvider)
        .updateJobStatus("job-123", "CANCELLED", null, "CANCELLED_BY_OWNER");
    verify(jobPersistenceProvider, never())
        .updateJobStatus(eq("job-123"), eq("FAILED"), any(), any());
  }

  @Test
  @DisplayName("A cancelled event never resurrects a deleted job")
  void onJobEvent_cancelledAfterDelete_leavesItDeleted() {
    JobDto deleted = job("job-123");
    deleted =
        new JobDto(
            deleted.id(),
            deleted.userId(),
            deleted.featureKey(),
            "DELETED",
            Map.of(),
            Map.of(),
            null,
            deleted.createdAt(),
            deleted.updatedAt());
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.of(deleted));

    listener.onJobEvent(Map.of("jobId", "job-123"), "job.job-123.cancelled");

    verify(jobPersistenceProvider, never()).updateJobStatus(any(), any(), any(), any());
  }

  @Test
  @DisplayName("A cancelled event for a job with no row here is not an error")
  void onJobEvent_cancelledWithoutRow_isIgnored() {
    when(jobPersistenceProvider.getJob("job-123")).thenReturn(Optional.empty());

    listener.onJobEvent(Map.of("jobId", "job-123"), "job.job-123.cancelled");

    verify(jobPersistenceProvider, never()).updateJobStatus(any(), any(), any(), any());
  }
}
