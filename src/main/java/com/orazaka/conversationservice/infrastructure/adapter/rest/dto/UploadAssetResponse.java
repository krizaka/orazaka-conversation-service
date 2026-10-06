package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable response payload returning metadata of an uploaded media asset.
 *
 * @param assetId Unique identifier of the uploaded asset.
 * @param filename Original filename of the asset.
 * @param contentType The mime content-type of the asset.
 * @param sizeBytes The size of the asset in bytes.
 */
public record UploadAssetResponse(
    UUID assetId, String filename, String contentType, long sizeBytes) {
  public UploadAssetResponse {
    Objects.requireNonNull(assetId, "assetId must not be null");
    Objects.requireNonNull(filename, "filename must not be null");
  }
}
