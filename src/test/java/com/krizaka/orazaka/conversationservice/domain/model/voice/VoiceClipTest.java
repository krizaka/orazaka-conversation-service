package com.krizaka.orazaka.conversationservice.domain.model.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceClipTest {

  /** A mono 16-bit WAV of {@code dataBytes} at {@code rate} Hz, optionally with a LIST chunk. */
  static byte[] wav(int rate, int dataBytes, boolean withList) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    fmt.putShort((short) 1)
        .putShort((short) 1)
        .putInt(rate)
        .putInt(rate * 2)
        .putShort((short) 2)
        .putShort((short) 16);
    out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(le(36 + dataBytes + (withList ? 12 : 0)));
    out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(le(16));
    out.writeBytes(fmt.array());
    if (withList) {
      out.writeBytes("LIST".getBytes(StandardCharsets.US_ASCII));
      out.writeBytes(le(4));
      out.writeBytes("INFO".getBytes(StandardCharsets.US_ASCII));
    }
    out.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(le(dataBytes));
    out.writeBytes(new byte[dataBytes]);
    return out.toByteArray();
  }

  private static byte[] le(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  @Test
  @DisplayName("A WAV's duration is read from its own header")
  void measuresWav() {
    VoiceClip clip = VoiceClip.of(wav(22_050, 44_100, false));

    assertThat(clip.contentType()).isEqualTo("audio/wav");
    assertThat(clip.durationMs()).isEqualTo(1_000L);
    assertThat(clip.seconds()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("A chunk before data does not turn into a duration")
  void walksTheChunks() {
    assertThat(VoiceClip.of(wav(16_000, 16_000, true)).durationMs()).isEqualTo(500L);
  }

  @Test
  @DisplayName("An MP3 is labelled as one, and its duration is not guessed")
  void mp3IsNotGuessed() {
    VoiceClip clip = VoiceClip.of(new byte[] {'I', 'D', '3', 4, 0, 0});

    assertThat(clip.contentType()).isEqualTo("audio/mpeg");
    assertThat(clip.durationMs()).isNull();
    assertThat(clip.seconds()).isNull();
  }

  @Test
  @DisplayName("Ogg is recognised; an empty clip is refused")
  void oggAndEmpty() {
    assertThat(VoiceClip.of(new byte[] {'O', 'g', 'g', 'S', 0}).contentType())
        .isEqualTo("audio/ogg");
    assertThatThrownBy(() -> VoiceClip.of(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("The audio is copied in and out, and compared by value")
  void valueSemantics() {
    byte[] bytes = wav(22_050, 100, false);
    VoiceClip clip = VoiceClip.of(bytes);
    bytes[0] = 0;

    assertThat(clip.audio()[0]).isEqualTo((byte) 'R');
    assertThat(clip).isEqualTo(VoiceClip.of(wav(22_050, 100, false)));
    assertThat(clip.hashCode()).isEqualTo(VoiceClip.of(wav(22_050, 100, false)).hashCode());
    assertThat(clip.toString()).contains("audio/wav").doesNotContain("RIFF");
  }
}
