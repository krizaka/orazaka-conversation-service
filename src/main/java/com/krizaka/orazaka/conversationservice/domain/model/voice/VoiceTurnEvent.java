package com.krizaka.orazaka.conversationservice.domain.model.voice;

import com.krizaka.orazaka.studio.domain.model.CrisisResource;

/**
 * What a voice turn tells its caller, in the order it happens (ADR-076).
 *
 * <p>Each event is one SSE frame: {@link #name()} is its {@code event:} line, the record its {@code
 * data:}. The vocabulary is closed on purpose — a client renders seven shapes and nothing else.
 */
public sealed interface VoiceTurnEvent {

  /**
   * @return the SSE event name
   */
  String name();

  /**
   * What was heard, before anything is answered.
   *
   * @param text the transcript
   * @param seconds how long the audio was, as the transcription engine measured it
   */
  record Transcript(String text, Double seconds) implements VoiceTurnEvent {
    @Override
    public String name() {
      return "transcript";
    }
  }

  /**
   * A piece of the answer as the model writes it — for captions that keep up with the voice.
   *
   * @param text the tokens
   */
  record Delta(String text) implements VoiceTurnEvent {
    @Override
    public String name() {
      return "delta";
    }
  }

  /**
   * One complete sentence, and its audio.
   *
   * <p>No {@code words} field: Piper, as LocalAI serves it, returns no word timings, and an
   * alignment invented here would be a lip-sync track that lies (ADR-076 §4). A client drives its
   * captions from {@code text} and its mouth from the signal.
   *
   * @param index 0-based position in the answer
   * @param text the exact text that was synthesised — the caption
   * @param audio where to fetch it
   */
  record Sentence(int index, String text, Audio audio) implements VoiceTurnEvent {
    @Override
    public String name() {
      return "sentence";
    }
  }

  /**
   * The crisis guard answered: the pack's reviewed text, never generated, and the region's line.
   *
   * @param response the reviewed text, as the pack wrote it
   * @param resource the verified line for the installation's region
   * @param text what was spoken: the response followed by the line
   * @param audio the spoken reply
   */
  record SafetyCrisis(String response, CrisisResource resource, String text, Audio audio)
      implements VoiceTurnEvent {
    @Override
    public String name() {
      return "safety.crisis";
    }
  }

  /**
   * The scope guard answered with the pack's own refusal.
   *
   * @param refusal the refusal, verbatim
   * @param audio the spoken refusal
   */
  record ScopeRefused(String refusal, Audio audio) implements VoiceTurnEvent {
    @Override
    public String name() {
      return "scope.refused";
    }
  }

  /**
   * The turn is over: what it consumed and how long each part took.
   *
   * @param usage the measurements the turn is billed on
   * @param timings where the time went, from the end of the upload
   */
  record Done(Usage usage, Timings timings) implements VoiceTurnEvent {
    @Override
    public String name() {
      return "done";
    }
  }

  /**
   * The turn failed after it started streaming.
   *
   * @param code a stable code a client may branch on
   * @param message what happened, never what the person said
   */
  record Failed(String code, String message) implements VoiceTurnEvent {
    @Override
    public String name() {
      return "error";
    }
  }

  /**
   * A synthesised clip, reachable for a short while.
   *
   * @param url a signed URL, valid {@code 300} seconds
   * @param durationMs how long it plays, when the format says
   * @param contentType its media type, read from its bytes
   * @param voice the catalogue voice that spoke it
   * @param locale the locale that voice speaks — not necessarily the turn's, see ADR-076
   */
  record Audio(String url, Long durationMs, String contentType, String voice, String locale) {}

  /**
   * What the turn consumed.
   *
   * @param sttSeconds seconds of audio transcribed; {@code null} for a text turn
   * @param chatTokens tokens the model reported; {@code null} when it reported none
   * @param ttsSeconds seconds of speech produced, where the format says
   * @param ttsCharacters characters synthesised — what speech is priced on
   */
  record Usage(Double sttSeconds, Integer chatTokens, Double ttsSeconds, Long ttsCharacters) {}

  /**
   * Where the time went, in milliseconds from the end of the upload.
   *
   * @param sttMs transcription
   * @param firstDeltaMs the model's first token
   * @param firstSentenceMs the first sentence's audio ready — the latency a person hears
   * @param totalMs the whole turn
   */
  record Timings(Long sttMs, Long firstDeltaMs, Long firstSentenceMs, Long totalMs) {}
}
