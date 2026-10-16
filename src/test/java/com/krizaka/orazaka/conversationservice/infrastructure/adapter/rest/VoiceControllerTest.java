package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.conversationservice.application.service.VoiceTurnService;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceClip;
import com.krizaka.orazaka.conversationservice.infrastructure.support.SseStreamGateway;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceClipStore;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceTurnRefusedException;
import com.krizaka.users.domain.model.User;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class VoiceControllerTest {

  private final VoiceTurnService service = mock(VoiceTurnService.class);
  private final VoiceClipStore clips = new VoiceClipStore();
  private final VoiceController controller =
      new VoiceController(service, new SseStreamGateway(), clips);

  @Test
  @DisplayName("A turn with neither audio nor text is a 400 before anything is prepared")
  void invalidTurnIs400() {
    assertThatThrownBy(
            () ->
                controller.takeTurn(
                    "conv", "fr-CA", null, null, null, null, null, mock(User.class)))
        .isInstanceOfSatisfying(
            VoiceTurnRefusedException.class,
            refused -> {
              assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(refused.code()).isEqualTo("invalid-voice-turn");
            });
    assertThatThrownBy(
            () ->
                controller.takeTurn(
                    "conv",
                    "fr-CA",
                    new MockMultipartFile("audio", new byte[] {1}),
                    "et du texte",
                    null,
                    null,
                    null,
                    mock(User.class)))
        .isInstanceOf(VoiceTurnRefusedException.class);
    verify(service, never()).prepare(any(), any());
  }

  @Test
  @DisplayName(
      "A clip is served by its signed URL, with its own type and no caching; anything else is 404")
  void servesSignedClips() {
    VoiceClip clip = VoiceClip.of(new byte[] {'I', 'D', '3', 9});
    Matcher url =
        Pattern.compile("clips/([^?]+)\\?expires=(\\d+)&signature=(\\w+)")
            .matcher(clips.keep(clip));
    assertThat(url.find()).isTrue();
    UUID id = UUID.fromString(url.group(1));
    long expires = Long.parseLong(url.group(2));

    var served = controller.clip(id, expires, url.group(3));
    assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(served.getHeaders().getContentType())
        .isEqualTo(MediaType.parseMediaType("audio/mpeg"));
    assertThat(served.getHeaders().getCacheControl()).contains("no-store");
    assertThat(served.getBody()).containsExactly('I', 'D', '3', 9);

    assertThat(controller.clip(id, expires, "forged").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
