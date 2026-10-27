package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.domain.port.AssetKeys;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.UploadAssetResponse;
import com.krizaka.orazaka.conversationservice.infrastructure.support.PathResolver;
import com.krizaka.orazaka.core.domain.model.security.Actor;
import com.krizaka.orazaka.core.domain.ports.outbound.KnowledgeService;
import com.krizaka.users.domain.model.User;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The three things left on the {@code media} resource once door 1 closed: uploading an asset,
 * deleting one's own upload, and searching the semantic index (ADR-068 §5).
 *
 * <p><b>What went, and why these two stayed.</b> {@code /analyze/image}, {@code /analyze/audio} and
 * {@code /analyze/video} each submitted a job straight to the job plane — a capability invocation
 * with no run behind it, and therefore with no data class, no retention by class, no audit row and
 * no scope guard. They are runs of the {@code orazaka-media} Studios now, and the endpoints are
 * gone.
 *
 * <p>Uploading is <b>not</b> a capability invocation: it stores bytes the caller already has, seals
 * them (ADR-054) and hands back an id the run path resolves against the actor who owns it. {@code
 * /search} is a read of an index. Neither dispatches work to a worker, neither takes credits, and
 * neither is a door.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaAnalysisController {

  private static final Logger logger = LoggerFactory.getLogger(MediaAnalysisController.class);

  private final KnowledgeService knowledgeService;
  private final String uploadDir;
  private final EncryptedAssetService encryptedAssetService;
  private final DurableAssetStore durableAssetStore;
  private final MediaStorageProperties storage;

  public MediaAnalysisController(
      KnowledgeService knowledgeService,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDirProperty,
      EncryptedAssetService encryptedAssetService,
      DurableAssetStore durableAssetStore,
      MediaStorageProperties storage) {
    this.durableAssetStore = durableAssetStore;
    this.storage = storage;
    this.knowledgeService = knowledgeService;
    this.uploadDir = PathResolver.resolveToString(uploadDirProperty);
    this.encryptedAssetService = encryptedAssetService;
  }

  /**
   * Performs a passive RAG context search against the semantic index, among what the caller may
   * read: the actor is built from the session, and a caller without one gets 401 (ADR-091).
   */
  @GetMapping("/search")
  public ResponseEntity<@NotNull Map<String, Object>> searchRag(
      @RequestParam("q") String query, @AuthenticationPrincipal User user) {
    Actor actor =
        user == null ? null : Actor.of(user.id().toString(), user.authorities()).orElse(null);
    if (actor == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    String context = knowledgeService.retrieveContext(actor, query, 5);
    return ResponseEntity.ok(Map.of("context", context));
  }

  /** Securely uploads a raw binary asset, isolated per authenticated user. */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<@NotNull Object> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "jobId", required = false) String jobId,
      @AuthenticationPrincipal User user) {

    if (file == null || file.isEmpty()) {
      return ResponseEntity.badRequest().body("File payload must not be empty");
    }
    if (user == null || user.id() == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user identity");
    }

    try {
      // Break taint chain: roundtrip through UUID.fromString validates format (Sonar S2083)
      String userId = UUID.fromString(user.id().toString()).toString();
      UUID assetId = UUID.randomUUID();

      // Sanitize user-controlled jobId against path traversal (Sonar S2083)
      String sanitizedJobId = sanitizeJobId(jobId);
      if (jobId != null && !jobId.isBlank() && sanitizedJobId == null) {
        return ResponseEntity.badRequest().body("Invalid jobId format");
      }

      Path basePath = Path.of(uploadDir).toRealPath();
      Path userDirPath;
      if (sanitizedJobId != null) {
        userDirPath = basePath.resolve(userId).resolve(sanitizedJobId).resolve("input");
      } else {
        userDirPath = basePath.resolve(userId).resolve("temp");
      }

      // Validate resolved path stays within the upload directory (Sonar S2083)
      if (!userDirPath.normalize().startsWith(basePath)) {
        return ResponseEntity.badRequest().body("Path traversal detected");
      }

      if (sanitizedJobId != null) {
        Files.createDirectories(userDirPath);
        Files.createDirectories(basePath.resolve(userId).resolve(sanitizedJobId).resolve("output"));
        Files.createDirectories(basePath.resolve(userId).resolve(sanitizedJobId).resolve("temp"));
      } else {
        Files.createDirectories(userDirPath);
        Files.createDirectories(basePath.resolve(userId).resolve("temp"));
      }

      // Sanitize original filename — extract only the extension (Sonar S2083)
      String originalFilename = file.getOriginalFilename();
      String suffix = extractSafeSuffix(originalFilename);
      String safeName = assetId.toString() + suffix;
      Path targetPath = userDirPath.resolve(safeName).normalize();

      // Final containment check (Sonar S2083)
      if (!targetPath.startsWith(userDirPath)) {
        return ResponseEntity.badRequest().body("Invalid file path");
      }

      // The identifying-document path. What a user uploads is sealed before it touches the disk:
      // there is no window in which the plaintext exists at the destination (ADR-054).
      try (InputStream source = file.getInputStream()) {
        encryptedAssetService.write(targetPath, source, file.getSize());
      }
      if (durableAssetStore.enabled()) {
        // The sealed envelope, under the uploader's own prefix. An upload reported stored here and
        // gone at the next deploy is the bug this prevents: a failed copy is a failed upload.
        durableAssetStore.put(AssetKeys.uploadFile(storage.tenant(), userId, safeName), targetPath);
      }
      // Log only non-user-controlled identifiers (Sonar S5145)
      logger.info("Successfully stored uploaded asset with id: {}", assetId);

      UploadAssetResponse responsePayload =
          new UploadAssetResponse(
              assetId,
              originalFilename != null ? originalFilename : safeName,
              file.getContentType(),
              file.getSize());
      return ResponseEntity.status(HttpStatus.CREATED).body(responsePayload);
    } catch (IOException e) {
      logger.error("Failed to write uploaded file to persistent storage", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to store asset payload");
    }
  }

  /**
   * Deletes one of the caller's own uploads (the sealed file in {@code <uploads>/<user>/temp}).
   *
   * <p>A product that sends a person's photo only to have it read once — a profile-photo check —
   * must be able to take it back the moment the answer is in; the retention sweep cannot do that
   * for an upload that belongs to no run. Only the caller's own directory is looked in, so another
   * account's asset id is a 404, exactly like an unknown one.
   */
  @DeleteMapping("/assets/{assetId}")
  public ResponseEntity<@NotNull Void> deleteAsset(
      @PathVariable("assetId") UUID assetId, @AuthenticationPrincipal User user) {
    if (user == null || user.id() == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    try {
      Path basePath = Path.of(uploadDir).toRealPath();
      Path temp =
          basePath
              .resolve(UUID.fromString(user.id().toString()).toString())
              .resolve("temp")
              .normalize();
      if (!temp.startsWith(basePath) || !Files.isDirectory(temp)) {
        return ResponseEntity.notFound().build();
      }
      boolean deleted = false;
      try (var files = Files.list(temp)) {
        for (Path file : (Iterable<Path>) files::iterator) {
          String name = file.getFileName().toString();
          if (name.equals(assetId.toString()) || name.startsWith(assetId + ".")) {
            Files.delete(file);
            deleted = true;
          }
        }
      }
      if (!deleted) {
        return ResponseEntity.notFound().build();
      }
      logger.info("Deleted uploaded asset with id: {}", assetId);
      return ResponseEntity.noContent().build();
    } catch (IOException e) {
      logger.error("Failed to delete an uploaded asset", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // ── Private helpers ──────────────────────────────────────────────────────

  private static String sanitizeJobId(String jobId) {
    if (jobId == null || jobId.isBlank()) {
      return null;
    }
    String sanitized = jobId.replaceAll("[^a-zA-Z0-9_-]", "");
    return sanitized.isEmpty() ? null : sanitized;
  }

  private static String extractSafeSuffix(String filename) {
    if (filename == null || !filename.contains(".")) {
      return "";
    }
    String ext = filename.substring(filename.lastIndexOf("."));
    return ext.replaceAll("[^a-zA-Z0-9.]", "");
  }
}
