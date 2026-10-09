package com.krizaka.orazaka.conversationservice.application.service;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.conversationservice.infrastructure.config.RouterProperties;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class JobReconciliationServiceTest {

  private static final Set<String> ACTIVE = Set.of("PENDING", "PROCESSING");
  private static final String USER_ID = "user-1";

  @TempDir Path tempDir;

  @Mock private JobPersistenceProvider jobPersistenceProvider;

  private final com.krizaka.orazaka.assets.application.service.EncryptedAssetService assets =
      testAssets();

  private JobReconciliationService service;

  @BeforeEach
  void setUp() {
    RouterProperties props =
        new RouterProperties(
            new RouterProperties.UploadsConfig(tempDir.toString(), "/uploads/**", 0));
    service =
        new JobReconciliationService(
            jobPersistenceProvider, new ObjectMapper(), props, assets, testEncryption());
  }

  private void writeResult(String jobId, String json) throws Exception {
    Path outputDir = tempDir.resolve(Path.of(USER_ID, jobId, "output"));
    Files.createDirectories(outputDir);
    // Through the store, like the executor that writes it: the reconciler reads an encrypted
    // result, and a fixture writing plaintext would test a path production no longer has.
    assets.write(
        outputDir.resolve("result.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private JobDto pendingJob(String jobId) {
    return new JobDto(
        jobId,
        USER_ID,
        "orazaka.core.media.image",
        "PENDING",
        Map.of(),
        Map.of(),
        null,
        Instant.now(),
        Instant.now());
  }

  @Test
  void reconcile_completedResultOnDisk_marksCompleted() throws Exception {
    String jobId = "job-ok";
    writeResult(jobId, "{\"url\":\"/uploads/u/j/output/image.png\",\"format\":\"png\"}");
    when(jobPersistenceProvider.findJobsByStatuses(ACTIVE)).thenReturn(List.of(pendingJob(jobId)));

    service.reconcileScheduled();

    verify(jobPersistenceProvider)
        .updateJobStatus(
            jobId,
            "COMPLETED",
            Map.of("url", "/uploads/u/j/output/image.png", "format", "png"),
            null);
  }

  @Test
  void reconcile_errorResultOnDisk_marksFailed() throws Exception {
    String jobId = "job-err";
    writeResult(jobId, "{\"error\":\"boom\"}");
    when(jobPersistenceProvider.findJobsByStatuses(ACTIVE)).thenReturn(List.of(pendingJob(jobId)));

    service.reconcileScheduled();

    verify(jobPersistenceProvider).updateJobStatus(jobId, "FAILED", null, "boom");
  }

  @Test
  void reconcile_noResultFileOnDisk_leavesJobUntouched() {
    when(jobPersistenceProvider.findJobsByStatuses(ACTIVE))
        .thenReturn(List.of(pendingJob("job-pending")));

    service.reconcileScheduled();

    verify(jobPersistenceProvider, never()).updateJobStatus(anyString(), anyString(), any(), any());
  }

  /** A real store over a throwaway keyring: tests exercise the format, never a stub of it. */
  static com.krizaka.orazaka.assets.application.service.EncryptedAssetService testAssets() {
    try {
      java.nio.file.Path keyFile =
          java.nio.file.Files.createTempDirectory("orz-keys").resolve("master.key");
      com.krizaka.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider.addKey(
          keyFile, "test");
      return new com.krizaka.orazaka.assets.application.service.EncryptedAssetService(
          new com.krizaka.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider(keyFile),
          4096);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** The cutover tolerance a unit test runs under: off, like production after the migration. */
  static com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties
      testEncryption() {
    return new com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties(
        true, "unused", 4096, false);
  }
}
