package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.core.domain.model.HostCapability;
import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import java.util.Locale;

/**
 * A model-catalog entry enriched with host hardware compatibility for the UI model picker.
 *
 * <p>{@code compatible} is {@code false} when the model's {@code supportedHardware} cannot run on
 * this host (e.g. a CUDA model on Apple Silicon); {@code incompatibleReason} then carries a
 * human-readable explanation (otherwise {@code null}). The client greys out incompatible models
 * instead of letting the user queue a job that will fail at generation time.
 *
 * <p>{@code requiresReferenceImage} is {@code true} for image-to-video models (SVD / img2vid),
 * which need a seed image; the client then surfaces a "reference image" affordance.
 */
public record CatalogModelResponse(
    Integer id,
    String modelName,
    String modelLabel,
    String category,
    String options,
    Boolean isDefault,
    String providerName,
    Integer maxSteps,
    Integer recommendedFps,
    String supportedHardware,
    String description,
    boolean compatible,
    String incompatibleReason,
    boolean requiresReferenceImage) {

  /** Builds a view from a catalog DTO, resolving compatibility against the host. */
  public static CatalogModelResponse from(CatalogModelDto dto, HostCapability host) {
    boolean compatible = host.supportsModelHardware(dto.supportedHardware());
    String reason =
        compatible
            ? null
            : "Requires " + dto.supportedHardware() + " — this host supports " + host.describe();
    return new CatalogModelResponse(
        dto.id(),
        dto.modelName(),
        dto.modelLabel(),
        dto.category(),
        dto.options(),
        dto.isDefault(),
        dto.providerName(),
        dto.maxSteps(),
        dto.recommendedFps(),
        dto.supportedHardware(),
        dto.description(),
        compatible,
        reason,
        isImageToVideo(dto.category(), dto.modelName()));
  }

  /**
   * Image-to-video models (Stable Video Diffusion / {@code img2vid}) need a seed image; text-to-
   * video models (AnimateDiff) do not. Inferred from the model name within the {@code video}
   * category.
   */
  private static boolean isImageToVideo(String category, String modelName) {
    if (!"video".equalsIgnoreCase(category) || modelName == null) {
      return false;
    }
    String name = modelName.toLowerCase(Locale.ROOT);
    return name.contains("img2vid")
        || name.contains("svd")
        || name.contains("stable-video-diffusion")
        || name.contains("stable-diffusion-video");
  }
}
