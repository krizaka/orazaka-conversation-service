package com.krizaka.orazaka.conversationservice.domain.model.voice;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * One synthesised sentence: its audio and what that audio measures (ADR-076).
 *
 * <p>The format and the duration are <b>read from the bytes</b>, never taken from the request: the
 * speech engine was asked for one format and answers in another (Piper through LocalAI returns WAV
 * whatever was asked), and a client told {@code mp3} for a WAV file plays silence. A WAV header
 * carries its own rate, so its duration is a measurement; a format whose duration cannot be read
 * here reports none rather than a guess.
 *
 * @param audio the clip
 * @param contentType the media type its first bytes declare
 * @param durationMs how long it plays, or {@code null} when the format does not say
 */
public record VoiceClip(byte[] audio, String contentType, Long durationMs) {

  /** Compact canonical constructor enforcing the record's invariants (ERR-106). */
  public VoiceClip {
    Objects.requireNonNull(audio, "audio must not be null");
    if (audio.length == 0) {
      throw new IllegalArgumentException("an empty clip is not a clip");
    }
    Objects.requireNonNull(contentType, "contentType must not be null");
    audio = audio.clone();
  }

  /**
   * Reads a synthesised clip's format and, for WAV, its duration from its own header.
   *
   * @param audio what the speech engine returned
   * @return the clip, measured
   */
  public static VoiceClip of(byte[] audio) {
    Objects.requireNonNull(audio, "audio must not be null");
    if (isWav(audio)) {
      return new VoiceClip(audio, "audio/wav", wavDurationMs(audio));
    }
    if (audio.length > 3
        && audio[0] == 'O'
        && audio[1] == 'g'
        && audio[2] == 'g'
        && audio[3] == 'S') {
      return new VoiceClip(audio, "audio/ogg", null);
    }
    return new VoiceClip(audio, "audio/mpeg", null);
  }

  @Override
  public byte[] audio() {
    return audio.clone();
  }

  /** Seconds of audio, as billing and the done event report them; {@code null} when unknown. */
  public Double seconds() {
    return durationMs == null ? null : durationMs / 1000.0;
  }

  private static boolean isWav(byte[] audio) {
    return audio.length >= 44
        && audio[0] == 'R'
        && audio[1] == 'I'
        && audio[2] == 'F'
        && audio[3] == 'F'
        && audio[8] == 'W'
        && audio[9] == 'A'
        && audio[10] == 'V'
        && audio[11] == 'E';
  }

  /**
   * Walks the RIFF chunks for {@code fmt } (byte rate) and {@code data} (length). Not the 44-byte
   * shortcut: an encoder that adds a {@code LIST} chunk moves {@code data}, and the shortcut reads
   * garbage as a length.
   */
  private static Long wavDurationMs(byte[] audio) {
    long byteRate = -1;
    int offset = 12;
    while (offset + 8 <= audio.length) {
      String id = new String(audio, offset, 4, StandardCharsets.US_ASCII);
      long size = le32(audio, offset + 4);
      if ("fmt ".equals(id) && offset + 16 <= audio.length) {
        byteRate = le32(audio, offset + 16);
      } else if ("data".equals(id)) {
        // A streamed WAV may declare 0 or 0xFFFFFFFF as its data size: then the rest of the file
        // is the data, which is what was actually returned.
        long dataSize = size == 0 || size == 0xFFFFFFFFL ? audio.length - (offset + 8L) : size;
        dataSize = Math.min(dataSize, audio.length - (offset + 8L));
        return byteRate > 0 ? dataSize * 1000 / byteRate : null;
      }
      offset += 8 + (int) Math.min(size + (size & 1), Integer.MAX_VALUE - offset - 8L);
    }
    return null;
  }

  private static long le32(byte[] b, int at) {
    return (b[at] & 0xFFL)
        | (b[at + 1] & 0xFFL) << 8
        | (b[at + 2] & 0xFFL) << 16
        | (b[at + 3] & 0xFFL) << 24;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VoiceClip(byte[] a, String type, Long ms)
        && Arrays.equals(audio, a)
        && contentType.equals(type)
        && Objects.equals(durationMs, ms);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(audio), contentType, durationMs);
  }

  @Override
  public String toString() {
    return "VoiceClip[" + audio.length + " bytes, " + contentType + ", " + durationMs + " ms]";
  }
}
