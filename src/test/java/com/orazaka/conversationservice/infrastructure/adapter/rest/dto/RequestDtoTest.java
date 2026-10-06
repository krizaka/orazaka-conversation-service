package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.orazaka.identity.domain.exception.InvalidRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestDtoTest {

  @Test
  void testCodeGenerationRequest() {
    CodeGenerationRequest req = new CodeGenerationRequest("generate a website", "gpt-4");
    assertEquals("generate a website", req.prompt());
    assertEquals("gpt-4", req.model());

    // blank/null prompt throws exception
    assertThrows(InvalidRequestException.class, () -> new CodeGenerationRequest(null, "model"));
    assertThrows(InvalidRequestException.class, () -> new CodeGenerationRequest("", "model"));
    assertThrows(InvalidRequestException.class, () -> new CodeGenerationRequest("  ", "model"));
  }

  @Test
  void testUploadAssetResponse() {
    UUID assetId = UUID.randomUUID();
    UploadAssetResponse resp = new UploadAssetResponse(assetId, "file.mp4", "video/mp4", 1024L);
    assertEquals(assetId, resp.assetId());
    assertEquals("file.mp4", resp.filename());
    assertEquals("video/mp4", resp.contentType());
    assertEquals(1024L, resp.sizeBytes());

    assertThrows(
        NullPointerException.class,
        () -> new UploadAssetResponse(null, "file.mp4", "video/mp4", 1024L));
    assertThrows(
        NullPointerException.class,
        () -> new UploadAssetResponse(assetId, null, "video/mp4", 1024L));
  }

  @Test
  void testChatStreamRequest() {
    ChatStreamRequest req =
        new ChatStreamRequest("hello", List.of("1"), "model", 10, 30, 20, "pipeline");
    assertEquals("hello", req.prompt());
    assertEquals(List.of("1"), req.assetIds());
    assertEquals("model", req.model());
    assertEquals(10, req.videoSteps());
    assertEquals(30, req.videoFps());
    assertEquals(20, req.motionBucketId());
    assertEquals("pipeline", req.pipelineId());

    // Null/blank defaults
    ChatStreamRequest defaults = new ChatStreamRequest("hello", null, null, null, null, null, null);
    assertEquals(List.of(), defaults.assetIds());
    assertEquals("default", defaults.pipelineId());

    ChatStreamRequest blankPipeline =
        new ChatStreamRequest("hello", null, null, null, null, null, "  ");
    assertEquals("default", blankPipeline.pipelineId());

    assertThrows(
        NullPointerException.class,
        () -> new ChatStreamRequest(null, null, null, null, null, null, null));
  }
}
