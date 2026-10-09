package com.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.krizaka.users.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestDtoTest {

  @Test
  @DisplayName("FeatureResponse carries a capability's identity and its live availability")
  void testFeatureResponse() {
    // It carried label, icon, uriPath, httpMethod and payloadTemplate, and REQUIRED the last three
    // non-null — which is why GET /api/v1/features answered 500 for any deployment with a
    // pack-contributed capability, whose uri_path is NULL (ADR-069 §5). What the endpoint answers
    // is availability; the columns behind those fields are gone.
    FeatureResponse valid = new FeatureResponse("orazaka.core.media.image", true, null);
    assertEquals("orazaka.core.media.image", valid.id());
    assertTrue(valid.available());
    assertNull(valid.lockedReason());

    FeatureResponse locked =
        new FeatureResponse("orazaka.core.media.video", false, "Engine offline");
    assertFalse(locked.available());
    assertEquals("Engine offline", locked.lockedReason());

    assertThrows(NullPointerException.class, () -> new FeatureResponse(null, true, null));
  }

  @Test
  @DisplayName("ImageGenerationRequest validates blank constraints in constructor")
  void testImageGenerationRequest() {
    ImageGenerationRequest valid = new ImageGenerationRequest("Generate poster", "dall-e-3");
    assertEquals("Generate poster", valid.prompt());
    assertEquals("dall-e-3", valid.model());

    assertThrows(InvalidRequestException.class, () -> new ImageGenerationRequest(null, "dall-e-3"));
    assertThrows(InvalidRequestException.class, () -> new ImageGenerationRequest(" ", "dall-e-3"));
  }

  @Test
  @DisplayName("SpeechRequest validates blank constraints in constructor")
  void testTtsRequest() {
    SpeechRequest valid = new SpeechRequest("Hello world", "tts-1", "alloy");
    assertEquals("Hello world", valid.text());
    assertEquals("tts-1", valid.model());
    assertEquals("alloy", valid.voice());

    assertThrows(InvalidRequestException.class, () -> new SpeechRequest(null, "tts-1", "alloy"));
    assertThrows(InvalidRequestException.class, () -> new SpeechRequest("   ", "tts-1", "alloy"));
  }
}
