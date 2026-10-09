package com.krizaka.orazaka.conversationservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetFileResolverTest {

  @TempDir Path tempDir;

  @Test
  void testResolveFromTempDir() throws IOException {
    // Setup directory structure: uploadDir/userId/temp
    Path uploadDir = tempDir.resolve("uploads");
    Path userDir = uploadDir.resolve("user123");
    Path userTempDir = userDir.resolve("temp");
    Files.createDirectories(userTempDir);

    Path tempFile = userTempDir.resolve("asset-abc-123.mp4");
    Files.writeString(tempFile, "temp content");

    Optional<File> resolved =
        AssetFileResolver.resolve(uploadDir.toString(), "user123", "asset-abc");

    assertThat(resolved).isPresent();
    assertThat(resolved.get()).hasName("asset-abc-123.mp4");
  }

  @Test
  void testResolveFromUserDir() throws IOException {
    // Setup directory structure: uploadDir/userId
    Path uploadDir = tempDir.resolve("uploads");
    Path userDir = uploadDir.resolve("user123");
    Files.createDirectories(userDir);

    Path userFile = userDir.resolve("asset-xyz-789.mp3");
    Files.writeString(userFile, "user content");

    Optional<File> resolved =
        AssetFileResolver.resolve(uploadDir.toString(), "user123", "asset-xyz");

    assertThat(resolved).isPresent();
    assertThat(resolved.get()).hasName("asset-xyz-789.mp3");
  }

  @Test
  void testResolveNotFound() throws IOException {
    Path uploadDir = tempDir.resolve("uploads");
    Path userDir = uploadDir.resolve("user123");
    Files.createDirectories(userDir);

    Optional<File> resolved =
        AssetFileResolver.resolve(uploadDir.toString(), "user123", "nonexistent");

    assertThat(resolved).isEmpty();
  }

  @Test
  void testResolveDirectoriesNotExist() {
    Optional<File> resolved =
        AssetFileResolver.resolve("/nonexistent-root-dir/uploads", "user123", "prefix");
    assertThat(resolved).isEmpty();
  }
}
