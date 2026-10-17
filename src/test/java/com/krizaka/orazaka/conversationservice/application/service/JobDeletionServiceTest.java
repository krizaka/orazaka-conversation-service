package com.krizaka.orazaka.conversationservice.application.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.config.RouterProperties;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.krizaka.web.problem.ConflictException;
import com.krizaka.web.problem.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner deletes a generated result (orazaka-web-client#86): files gone from every store, row
 * marked.
 */
class JobDeletionServiceTest {

  private static final String OWNER = "user-1";
  private static final String JOB = "job-1";

  @TempDir Path uploads;

  private final JobPersistenceProvider jobs = mock(JobPersistenceProvider.class);
  private final DurableAssetStore durable = mock(DurableAssetStore.class);
  private JobDeletionService service;

  @BeforeEach
  void wire() throws IOException {
    when(durable.enabled()).thenReturn(true);
    service =
        new JobDeletionService(
            jobs,
            new RouterProperties(
                new RouterProperties.UploadsConfig(uploads.toString(), "/uploads/**", 0)),
            durable,
            new MediaStorageProperties("local", null, null, "storage.bunnycdn.com", null, "t1"));
    Path output = uploads.resolve(OWNER).resolve(JOB).resolve("output");
    Files.createDirectories(output);
    Files.writeString(output.resolve("video.mp4"), "sealed");
  }

  private void job(String status, String userId) {
    when(jobs.getJob(JOB))
        .thenReturn(
            Optional.of(
                new JobDto(
                    JOB,
                    userId,
                    "orazaka.core.media.video",
                    status,
                    Map.of("prompt", "a heron"),
                    Map.of("url", "/api/v1/assets/job-1/video.mp4"),
                    null,
                    Instant.parse("2026-10-16T10:00:00Z"),
                    Instant.parse("2026-10-16T10:01:00Z"))));
  }

  @Test
  void theOwnerDeletesAFinishedResult_filesAndRow() throws Exception {
    job("COMPLETED", OWNER);

    service.delete(JOB, OWNER, false);

    assertFalse(Files.exists(uploads.resolve(OWNER).resolve(JOB)), "local files are gone");
    verify(durable).delete("t/t1/u/user-1/jobs/job-1/");
    verify(jobs).updateJobStatus(JOB, "DELETED", Map.of(), null);
  }

  @Test
  void anotherUserGetsA404_andNothingIsTouched() throws Exception {
    job("COMPLETED", OWNER);

    assertThrows(NotFoundException.class, () -> service.delete(JOB, "intruder", false));

    assertTrue(Files.exists(uploads.resolve(OWNER).resolve(JOB).resolve("output/video.mp4")));
    verify(durable, never()).delete(any());
    verify(jobs, never()).updateJobStatus(any(), any(), any(), any());
  }

  @Test
  void anAdminMayDeleteForAUser() throws Exception {
    job("FAILED", OWNER);

    service.delete(JOB, "admin-9", true);

    verify(jobs).updateJobStatus(JOB, "DELETED", Map.of(), null);
  }

  @Test
  void aJobStillRunningIsRefused_cancelItFirst() throws Exception {
    job("PROCESSING", OWNER);

    assertThrows(ConflictException.class, () -> service.delete(JOB, OWNER, false));

    verify(jobs, never()).updateJobStatus(any(), any(), any(), any());
  }

  @Test
  void aStoreThatRefuses_leavesTheRowUntouched_soTheDeleteCanBeRetried() throws Exception {
    job("COMPLETED", OWNER);
    org.mockito.Mockito.doThrow(new IOException("bunny 503")).when(durable).delete(any());

    assertThrows(IllegalStateException.class, () -> service.delete(JOB, OWNER, false));

    verify(jobs, never()).updateJobStatus(any(), any(), any(), any());
  }

  @Test
  void deletingTwiceIsNotAnError() throws Exception {
    job("DELETED", OWNER);

    service.delete(JOB, OWNER, false);

    verify(jobs, never()).updateJobStatus(any(), any(), any(), any());
  }
}
