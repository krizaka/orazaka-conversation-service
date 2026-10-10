package com.krizaka.orazaka.conversationservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Immutable configuration properties record for the {@code orazaka-router} module.
 *
 * <p>Maps properties under the {@code orazaka.router} prefix in {@code application.yml}.
 *
 * @param uploads Upload directory, handler path, and cache configuration.
 */
@ConfigurationProperties(prefix = "orazaka.router")
public record RouterProperties(@DefaultValue UploadsConfig uploads) {

  /**
   * Upload-related configuration.
   *
   * @param directory Relative path to the upload directory.
   * @param handlerPath URL pattern for serving uploaded files.
   * @param cachePeriod Cache period in seconds for static resources (0 = no cache).
   */
  public record UploadsConfig(
      String directory,
      @DefaultValue("/uploads/**") String handlerPath,
      @DefaultValue("0") int cachePeriod) {
    public UploadsConfig {
      if (directory == null || directory.isBlank()) {
        throw new IllegalArgumentException("Upload directory must not be blank");
      }
      if (handlerPath == null || handlerPath.isBlank()) {
        throw new IllegalArgumentException("Upload handler path must not be blank");
      }
    }
  }
}
