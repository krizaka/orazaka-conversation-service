package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.UploadAssetResponse;
import com.krizaka.orazaka.core.domain.ports.outbound.KnowledgeService;
import com.krizaka.users.domain.model.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class MediaAnalysisControllerTest {

  @TempDir Path tempDir;

  @Mock private KnowledgeService knowledgeService;

  private MediaAnalysisController controller;

  private static User user(UUID id) {
    return new User(id, "testuser", "test@example.com", true, Set.of("ROLE_USER"), Map.of());
  }

  @BeforeEach
  void setUp() {
    controller =
        controllerWith(new com.krizaka.orazaka.assets.infrastructure.storage.NoDurableAssetStore());
  }

  private MediaAnalysisController controllerWith(
      com.krizaka.orazaka.assets.domain.port.DurableAssetStore durable) {
    return new MediaAnalysisController(
        knowledgeService,
        tempDir.toString(),
        testAssets(),
        durable,
        new com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties(
            "directory", null, null, "storage.bunnycdn.com", "x", "acme"));
  }

  @Test
  void upload_isKeptDurablyAsTheSealedEnvelope_underTheUploadersPrefix() throws Exception {
    var durable =
        new com.krizaka.orazaka.assets.infrastructure.storage.DirectoryAssetStore(
            tempDir.resolve("durable"));
    UUID userId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", "fake-png-data".getBytes());

    ResponseEntity<Object> response = controllerWith(durable).upload(file, null, user(userId));

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    var uploads = tempDir.resolve("durable/t/acme/u/" + userId + "/jobs/uploads");
    try (var files = Files.list(uploads)) {
      var kept = files.toList();
      org.junit.jupiter.api.Assertions.assertEquals(1, kept.size());
      org.junit.jupiter.api.Assertions.assertTrue(
          kept.getFirst().getFileName().toString().endsWith(".png"));
      org.junit.jupiter.api.Assertions.assertTrue(
          testAssets().isEncrypted(kept.getFirst()), "only sealed bytes leave the disk");
    }
  }

  @Test
  void upload_whenTheDurableCopyFails_isNotReportedStored() {
    var broken =
        new com.krizaka.orazaka.assets.domain.port.DurableAssetStore() {
          public boolean enabled() {
            return true;
          }

          public void put(String key, Path file) throws java.io.IOException {
            throw new java.io.IOException("HTTP 401");
          }

          public boolean fetch(String key, Path target) {
            return false;
          }

          public void delete(String key) {}
        };
    MultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", "fake-png-data".getBytes());

    ResponseEntity<Object> response =
        controllerWith(broken).upload(file, null, user(UUID.randomUUID()));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void searchRag_returnsContext() {
    when(knowledgeService.retrieveContext("cats", 5)).thenReturn("ctx-about-cats");
    ResponseEntity<Map<String, Object>> response = controller.searchRag("cats");
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("ctx-about-cats", response.getBody().get("context"));
  }

  @Test
  void upload_emptyFile_returnsBadRequest() {
    MultipartFile file = new MockMultipartFile("file", "", "text/plain", new byte[0]);
    ResponseEntity<Object> response = controller.upload(file, null, user(UUID.randomUUID()));
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("File payload must not be empty", response.getBody());
  }

  @Test
  void upload_nullUser_returnsUnauthorized() {
    MultipartFile file =
        new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());
    ResponseEntity<Object> response = controller.upload(file, null, null);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertEquals("Unauthorized user identity", response.getBody());
  }

  @Test
  void upload_validFileWithoutJobId_savesToTempAndReturnsCreated() throws Exception {
    UUID userId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", "fake-png-data".getBytes());
    Files.createDirectories(tempDir.toRealPath());

    ResponseEntity<Object> response = controller.upload(file, null, user(userId));

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertTrue(response.getBody() instanceof UploadAssetResponse);
    UploadAssetResponse body = (UploadAssetResponse) response.getBody();
    assertNotNull(body.assetId());
    assertEquals("photo.png", body.filename());
    assertEquals("image/png", body.contentType());
    assertEquals(13L, body.sizeBytes());

    Path userTempDir = tempDir.resolve(userId.toString()).resolve("temp");
    assertTrue(Files.exists(userTempDir));
    try (var filesStream = Files.list(userTempDir)) {
      assertEquals(1, filesStream.count());
    }
  }

  @Test
  void upload_validFileWithJobId_savesToJobInputAndReturnsCreated() throws Exception {
    UUID userId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "video.mp4", "video/mp4", "fake-mp4-data".getBytes());
    Files.createDirectories(tempDir.toRealPath());

    ResponseEntity<Object> response = controller.upload(file, "job-999", user(userId));

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertTrue(response.getBody() instanceof UploadAssetResponse);

    Path jobBase = tempDir.resolve(userId.toString()).resolve("job-999");
    assertTrue(Files.exists(jobBase.resolve("input")));
    assertTrue(Files.exists(jobBase.resolve("output")));
    assertTrue(Files.exists(jobBase.resolve("temp")));
    try (var filesStream = Files.list(jobBase.resolve("input"))) {
      assertEquals(1, filesStream.count());
    }
  }

  @Test
  void deleteAsset_removesTheCallersOwnUpload() throws Exception {
    UUID userId = UUID.randomUUID();
    Files.createDirectories(tempDir.toRealPath());
    UploadAssetResponse uploaded =
        (UploadAssetResponse)
            controller
                .upload(
                    new MockMultipartFile("file", "p.jpg", "image/jpeg", "face-bytes".getBytes()),
                    null,
                    user(userId))
                .getBody();

    ResponseEntity<Void> response = controller.deleteAsset(uploaded.assetId(), user(userId));

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    try (var left = Files.list(tempDir.resolve(userId.toString()).resolve("temp"))) {
      assertEquals(0, left.count(), "the sealed bytes are gone from the disk");
    }
  }

  @Test
  void deleteAsset_ofSomeoneElse_isNotFoundAndKeepsTheFile() throws Exception {
    UUID owner = UUID.randomUUID();
    Files.createDirectories(tempDir.toRealPath());
    UploadAssetResponse uploaded =
        (UploadAssetResponse)
            controller
                .upload(
                    new MockMultipartFile("file", "p.jpg", "image/jpeg", "face-bytes".getBytes()),
                    null,
                    user(owner))
                .getBody();

    ResponseEntity<Void> response =
        controller.deleteAsset(uploaded.assetId(), user(UUID.randomUUID()));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    try (var left = Files.list(tempDir.resolve(owner.toString()).resolve("temp"))) {
      assertEquals(1, left.count());
    }
  }

  @Test
  void deleteAsset_unknownOrAlreadyDeleted_isNotFound() throws Exception {
    Files.createDirectories(tempDir.toRealPath());
    assertEquals(
        HttpStatus.NOT_FOUND,
        controller.deleteAsset(UUID.randomUUID(), user(UUID.randomUUID())).getStatusCode());
  }

  @Test
  void deleteAsset_withoutUser_isUnauthorized() {
    assertEquals(
        HttpStatus.UNAUTHORIZED, controller.deleteAsset(UUID.randomUUID(), null).getStatusCode());
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
