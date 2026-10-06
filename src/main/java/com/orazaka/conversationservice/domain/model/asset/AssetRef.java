package com.orazaka.conversationservice.domain.model.asset;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A stored media file, identified by the job that produced it and its owner.
 *
 * <p>The owner is the {@code userId} recorded on the job, never a value taken from the request.
 * That distinction is the whole point of this type: the previous static handler derived the path
 * straight from the URL, so the first path segment *was* the authorisation decision and any tenant
 * could read any other's tree by editing it.
 *
 * <p>Containment lives here rather than in a {@code PathUtil} [ERR-127]: this record owns the path,
 * so it owns the guarantee that resolving it cannot escape the upload root. A caller that holds an
 * {@code AssetRef} cannot obtain an unchecked path from it.
 *
 * @param ownerId the job's recorded owner
 * @param jobId the job that produced the file
 * @param filename the file's name within the job's output directory
 */
public record AssetRef(String ownerId, String jobId, String filename) {

  /** The subdirectory {@code MediaFileStore} writes results into. */
  private static final String OUTPUT_DIR = "output";

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public AssetRef {
    ownerId = requireUsable(ownerId, "ownerId");
    jobId = requireUsable(jobId, "jobId");
    filename = requireUsable(filename, "filename");

    // Rejected at construction, not at resolution: a traversal attempt is a malformed reference,
    // and a type that can hold one is a type every caller has to remember to re-check.
    if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
      throw new IllegalArgumentException("filename must not contain a path separator or '..'");
    }
  }

  private static String requireUsable(String value, String field) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /**
   * Resolves this reference under an upload root, proving the result stays inside it.
   *
   * @param uploadRoot the absolute upload root
   * @return {@code <root>/<ownerId>/<jobId>/output/<filename>}, normalised
   * @throws IllegalArgumentException if the resolved path escapes the root — belt and braces behind
   *     the constructor's check, because the owner and job ids also come from storage and a future
   *     writer of those columns is not bound by this record's constructor
   */
  public Path resolveUnder(Path uploadRoot) {
    Objects.requireNonNull(uploadRoot, "uploadRoot must not be null");
    Path root = uploadRoot.toAbsolutePath().normalize();
    Path resolved =
        root.resolve(ownerId).resolve(jobId).resolve(OUTPUT_DIR).resolve(filename).normalize();

    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("asset path escapes the upload root");
    }
    return resolved;
  }
}
