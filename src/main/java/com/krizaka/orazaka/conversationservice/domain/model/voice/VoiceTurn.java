package com.krizaka.orazaka.conversationservice.domain.model.voice;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * A voice turn as the transport hands it over, validated by its owner (ERR-106).
 *
 * @param conversationId the conversation the turn belongs to
 * @param locale the BCP 47 tag the person speaks and wants to be answered in
 * @param audio the recording, at most {@link #MAX_AUDIO_BYTES}; or {@code null} for a text turn
 * @param text what the person typed; or {@code null} for an audio turn
 * @param voice a catalogued voice, or {@code null} for the locale's default
 * @param installationId the installation whose pack governs the turn, or {@code null}
 * @param context the caller's context, injected after the pack's prompt; at most {@link
 *     #MAX_CONTEXT_CHARACTERS}
 */
public record VoiceTurn(
    String conversationId,
    String locale,
    byte[] audio,
    String text,
    String voice,
    UUID installationId,
    String context) {

  /**
   * 30 seconds of 48 kHz 16-bit mono WAV is 2.9 MB; a compressed recording is far smaller. The
   * duration itself is the engine's to measure — this bounds what a request may make it read.
   */
  public static final int MAX_AUDIO_BYTES = 3 * 1024 * 1024;

  /**
   * The caller's context, bounded: 2 000 tokens, counted as 8 000 characters at four per token — no
   * tokenizer is shared by every model the router may pick, and an approximation that errs shorter
   * keeps the pack's prompt from being drowned rather than truncated.
   */
  public static final int MAX_CONTEXT_CHARACTERS = 8_000;

  /** Compact canonical constructor enforcing the turn's invariants (ERR-106). */
  public VoiceTurn {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("a voice turn belongs to a conversation");
    }
    if (locale == null || !locale.matches("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$")) {
      throw new IllegalArgumentException("locale must be a BCP 47 tag such as fr-CA");
    }
    boolean hasAudio = audio != null && audio.length > 0;
    boolean hasText = text != null && !text.isBlank();
    if (hasAudio == hasText) {
      throw new IllegalArgumentException("a voice turn carries audio or text — exactly one");
    }
    if (hasAudio && audio.length > MAX_AUDIO_BYTES) {
      throw new IllegalArgumentException("audio is limited to about 30 seconds");
    }
    if (context != null && context.length() > MAX_CONTEXT_CHARACTERS) {
      throw new IllegalArgumentException(
          "context is limited to 2 000 tokens (" + MAX_CONTEXT_CHARACTERS + " characters)");
    }
    audio = hasAudio ? audio.clone() : null;
    text = hasText ? text.strip() : null;
    voice = voice == null || voice.isBlank() ? null : voice.strip();
    context = context == null || context.isBlank() ? null : context.strip();
  }

  @Override
  public byte[] audio() {
    return audio == null ? null : audio.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VoiceTurn that
        && conversationId.equals(that.conversationId)
        && locale.equals(that.locale)
        && Arrays.equals(audio, that.audio)
        && Objects.equals(text, that.text)
        && Objects.equals(voice, that.voice)
        && Objects.equals(installationId, that.installationId)
        && Objects.equals(context, that.context);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        conversationId, locale, Arrays.hashCode(audio), text, voice, installationId, context);
  }

  @Override
  public String toString() {
    // Never the text or the audio: a log keeps no data class (ADR-064).
    return "VoiceTurn[conversation=" + conversationId + ", locale=" + locale + "]";
  }
}
