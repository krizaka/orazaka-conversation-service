package com.orazaka.conversationservice.infrastructure.support;

import java.io.File;
import java.util.Optional;

/**
 * Package-private utility for resolving uploaded asset files from the user's storage hierarchy.
 *
 * <p>Eliminates the 3× duplicated file-lookup pattern that scanned {@code temp/} then the user root
 * directory in {@code ChatStreamController}.
 *
 * <p>Lookup order:
 *
 * <ol>
 *   <li>{@code {uploadDir}/{userId}/temp/} — files awaiting processing
 *   <li>{@code {uploadDir}/{userId}/} — persisted user files
 * </ol>
 */
public final class AssetFileResolver {

  private AssetFileResolver() {}

  /**
   * Resolves a file by its asset ID prefix from the user's upload directory hierarchy.
   *
   * @param uploadDir the root upload directory path
   * @param userId the user's unique identifier
   * @param assetId the asset ID used as a filename prefix
   * @return the located file, or empty if not found in any directory
   */
  public static Optional<File> resolve(String uploadDir, String userId, Object assetId) {
    String prefix = assetId.toString();
    File userDir = new File(uploadDir, userId);

    // Priority 1: temp directory
    File tempDir = new File(userDir, "temp");
    Optional<File> found = findByPrefix(tempDir, prefix);
    if (found.isPresent()) {
      return found;
    }

    // Priority 2: user root directory
    return findByPrefix(userDir, prefix);
  }

  private static Optional<File> findByPrefix(File directory, String prefix) {
    if (!directory.exists()) {
      return Optional.empty();
    }
    File[] matches = directory.listFiles((dir, name) -> name.startsWith(prefix));
    if (matches != null && matches.length > 0) {
      return Optional.of(matches[0]);
    }
    return Optional.empty();
  }
}
