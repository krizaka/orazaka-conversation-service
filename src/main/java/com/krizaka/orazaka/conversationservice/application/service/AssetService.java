package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.krizaka.orazaka.conversationservice.domain.model.asset.AssetRef;
import com.krizaka.orazaka.conversationservice.infrastructure.config.RouterProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.support.EncryptedFileResource;
import com.krizaka.orazaka.conversationservice.infrastructure.support.PathResolver;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Resolves a stored media file for a caller who is allowed to read it.
 *
 * <p>Everything this class exists to enforce is in {@link #resolveFor}: the owner comes from the
 * job record, never from the URL. The path the caller asked for is used only to *name* the file
 * inside that owner's directory.
 */
@Service
public class AssetService {

  private static final Logger logger = LoggerFactory.getLogger(AssetService.class);

  private final JobPersistenceProvider jobPersistenceProvider;
  private final RouterProperties routerProperties;
  private final EncryptedAssetService encryptedAssetService;
  private final AssetEncryptionProperties encryptionProperties;

  public AssetService(
      JobPersistenceProvider jobPersistenceProvider,
      RouterProperties routerProperties,
      EncryptedAssetService encryptedAssetService,
      AssetEncryptionProperties encryptionProperties) {
    this.jobPersistenceProvider =
        Objects.requireNonNull(jobPersistenceProvider, "JobPersistenceProvider required");
    this.routerProperties = Objects.requireNonNull(routerProperties, "RouterProperties required");
    this.encryptedAssetService =
        Objects.requireNonNull(encryptedAssetService, "EncryptedAssetService required");
    this.encryptionProperties =
        Objects.requireNonNull(encryptionProperties, "AssetEncryptionProperties required");
  }

  /**
   * Resolves an asset for an actor, or empty when they may not read it.
   *
   * <p>Empty is returned for "no such job", "not your job" and "no such file" alike, and the caller
   * turns all three into {@code 404}. Distinguishing them would answer "does this job exist?" to
   * anyone willing to ask, which is the enumeration the fix exists to prevent — a {@code 403} is a
   * confirmation.
   *
   * @param jobId the job that produced the file
   * @param filename the file within that job's output directory
   * @param actorId the authenticated caller
   * @param isAdmin whether the caller holds {@code ROLE_ADMIN}
   * @return the readable file, or empty
   */
  public Optional<Resource> resolveFor(
      String jobId, String filename, String actorId, boolean isAdmin) {
    Optional<JobDto> job = jobPersistenceProvider.getJob(jobId);
    if (job.isEmpty()) {
      return Optional.empty();
    }
    String owner = job.get().userId();
    if (!isAdmin && !Objects.equals(owner, actorId)) {
      logger.info("Denied asset read: actor {} is not the owner of job {}", actorId, jobId);
      return Optional.empty();
    }
    if (isAdmin && !Objects.equals(owner, actorId)) {
      // Logged rather than silent: an administrator reading someone else's media is legitimate
      // (the jobs dashboard needs it) and is exactly the access an audit trail must retain.
      logger.info("Admin {} is reading an asset owned by {} (job {})", actorId, owner, jobId);
    }

    Path uploadRoot = PathResolver.resolve(routerProperties.uploads().directory());
    Path file;
    try {
      file = new AssetRef(owner, jobId, filename).resolveUnder(uploadRoot);
    } catch (IllegalArgumentException ex) {
      logger.info("Rejected asset reference for job {}: {}", jobId, ex.getMessage());
      return Optional.empty();
    }

    if (!Files.isReadable(file) || Files.isDirectory(file)) {
      return Optional.empty();
    }
    return readable(file);
  }

  /**
   * The file as a servable resource, decrypting if it is an envelope.
   *
   * <p>An {@link EncryptedFileResource} rather than bytes: {@code Content-Length} is the plaintext
   * length from the header, and a {@code Range} is served by seeking to the block that contains its
   * start. Reading the whole file to answer a range would give back exactly what block addressing
   * bought (ADR-054 §4).
   *
   * <p>A plaintext file is served only while {@code accept-plaintext} is on, which is a migration
   * state. Once off, an unconverted file is <b>not readable</b> — a store that keeps serving
   * plaintext forever has an encryption setting, not encryption.
   */
  private Optional<Resource> readable(Path file) {
    if (encryptedAssetService.isEncrypted(file)) {
      return Optional.of(new EncryptedFileResource(file, encryptedAssetService));
    }
    if (encryptionProperties.acceptPlaintext()) {
      logger.debug("Serving {} as plaintext: it predates the encryption migration", file);
      return Optional.of(new FileSystemResource(file));
    }
    logger.warn(
        "Refusing to serve {}: it is not encrypted and accept-plaintext is off. Run"
            + " `orazaka assets migrate` — an unconverted file is unreadable, not plaintext.",
        file);
    return Optional.empty();
  }
}
