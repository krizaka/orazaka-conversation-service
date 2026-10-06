package com.orazaka.conversationservice.infrastructure.support;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaFileStoreTest {

  @TempDir Path tempDir;

  private final com.orazaka.assets.application.service.EncryptedAssetService assets = testAssets();
  private final MediaFileStore store = new MediaFileStore(assets);

  @Test
  void save_nullOrEmptyData_returnsEmptyString() {
    assertEquals("", store.save(tempDir.toString(), "user1", "job1", null, "file.mp4"));
    assertEquals("", store.save(tempDir.toString(), "user1", "job1", new byte[0], "file.mp4"));
  }

  @Test
  void save_validData_savesAndReturnsUrl() throws Exception {
    byte[] data = new byte[] {1, 2, 3, 4};
    String result = store.save(tempDir.toString(), "user1", "job1", data, "file.mp4");

    assertEquals("/api/v1/assets/job1/file.mp4", result);

    Path savedPath = tempDir.resolve("user1").resolve("job1").resolve("output").resolve("file.mp4");
    assertTrue(Files.exists(savedPath));
    // Encrypted at rest (ADR-054): the bytes on disk are an envelope, and the store hands the
    // originals back. Asserting the raw file equalled the input is what a plaintext store does.
    assertFalse(java.util.Arrays.equals(data, Files.readAllBytes(savedPath)));
    assertArrayEquals(data, assets.readAllBytes(savedPath, false));
  }

  @Test
  void save_ioException_returnsEmptyString() throws Exception {
    byte[] data = new byte[] {1, 2, 3, 4};
    // Create a file where a directory is expected, forcing createDirectories to fail.
    Files.write(tempDir.resolve("user1"), new byte[] {1});

    assertEquals("", store.save(tempDir.toString(), "user1", "job1", data, "file.mp4"));
  }

  /** A real store over a throwaway keyring: tests exercise the format, never a stub of it. */
  static com.orazaka.assets.application.service.EncryptedAssetService testAssets() {
    try {
      java.nio.file.Path keyFile =
          java.nio.file.Files.createTempDirectory("orz-keys").resolve("master.key");
      com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider.addKey(keyFile, "test");
      return new com.orazaka.assets.application.service.EncryptedAssetService(
          new com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider(keyFile), 4096);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
