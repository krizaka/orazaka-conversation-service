package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.application.service.VoiceTurnService;
import com.krizaka.orazaka.conversationservice.domain.model.voice.PreparedTurn;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceClip;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceTurn;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceTurnEvent;
import com.krizaka.orazaka.conversationservice.infrastructure.support.SseStreamGateway;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceClipStore;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceTurnRefusedException;
import com.krizaka.users.domain.model.User;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The {@code voice} resource: a conversational turn taken by voice, and the clips it speaks
 * (ADR-076).
 *
 * <p>Translates transport into a {@link VoiceTurn} and a stream of {@link VoiceTurnEvent}s back
 * into SSE frames — nothing else. Everything that can refuse a turn with a status (an unknown
 * installation, consent to an older statement, a plan without speech, no voice for the locale, no
 * credits) is decided before the stream opens, so a refusal is an HTTP status and never half a
 * stream. SSE and not WebSocket: the organisation's doctrine, and a turn is one request with many
 * answers.
 */
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceController {

  private static final Logger logger = LoggerFactory.getLogger(VoiceController.class);

  private final VoiceTurnService voiceTurns;
  private final SseStreamGateway sse;
  private final VoiceClipStore clips;

  /**
   * @param voiceTurns takes the turn
   * @param sse creates the stream
   * @param clips serves what the turn spoke
   */
  public VoiceController(VoiceTurnService voiceTurns, SseStreamGateway sse, VoiceClipStore clips) {
    this.voiceTurns = Objects.requireNonNull(voiceTurns, "VoiceTurnService required");
    this.sse = Objects.requireNonNull(sse, "SseStreamGateway required");
    this.clips = Objects.requireNonNull(clips, "VoiceClipStore required");
  }

  /**
   * Takes a turn: audio (WAV, Ogg/Opus, WebM, MP3; ~30 s at most) or text, answered over SSE —
   * {@code transcript}, {@code delta}, {@code sentence} (text and a signed audio URL valid 300 s),
   * {@code safety.crisis} or {@code scope.refused} when a guard answers, then {@code done} with the
   * turn's usage and timings.
   *
   * @param conversationId the conversation
   * @param locale the BCP 47 tag the person speaks
   * @param audio the recording, or absent for a text turn
   * @param text what the person typed, or absent for an audio turn
   * @param voice a catalogued voice; absent for the locale's default
   * @param installationId the installation whose pack governs the turn
   * @param context the caller's context, placed after the pack's prompt (2 000 tokens at most)
   * @param user the authenticated caller
   * @return the event stream
   */
  @PostMapping(
      value = "/turns",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter takeTurn(
      @RequestParam String conversationId,
      @RequestParam String locale,
      @RequestPart(required = false) MultipartFile audio,
      @RequestParam(required = false) String text,
      @RequestParam(required = false) String voice,
      @RequestParam(required = false) UUID installationId,
      @RequestParam(required = false) String context,
      @AuthenticationPrincipal User user) {
    VoiceTurn turn = turnOf(conversationId, locale, audio, text, voice, installationId, context);
    PreparedTurn prepared = voiceTurns.prepare(turn, user);
    SseEmitter emitter = sse.createEmitter();
    Thread.ofVirtual()
        .name("voice-turn-" + prepared.turnId())
        .start(
            () -> {
              voiceTurns.run(prepared, event -> send(emitter, event));
              try {
                emitter.complete();
              } catch (IllegalStateException alreadyClosed) {
                logger.trace("Voice turn stream already closed");
              }
            });
    return emitter;
  }

  /**
   * One spoken sentence, by its signed URL — no bearer token, because an {@code <audio>} element
   * cannot send one; the signature and its five-minute expiry are the authorisation.
   *
   * @param clipId the clip
   * @param expires the expiry the URL carries (epoch seconds)
   * @param signature the URL's signature
   * @return the audio; {@code 404} for a forged, expired or unknown URL alike
   */
  @GetMapping("/clips/{clipId}")
  public ResponseEntity<byte[]> clip(
      @PathVariable UUID clipId, @RequestParam long expires, @RequestParam String signature) {
    return clips
        .fetch(clipId, expires, signature)
        .map(
            (VoiceClip found) ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(found.contentType()))
                    .cacheControl(CacheControl.noStore())
                    .body(found.audio()))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  private static VoiceTurn turnOf(
      String conversationId,
      String locale,
      MultipartFile audio,
      String text,
      String voice,
      UUID installationId,
      String context) {
    try {
      byte[] recording = audio == null || audio.isEmpty() ? null : audio.getBytes();
      return new VoiceTurn(conversationId, locale, recording, text, voice, installationId, context);
    } catch (IllegalArgumentException invalid) {
      throw new VoiceTurnRefusedException(
          HttpStatus.BAD_REQUEST, "invalid-voice-turn", invalid.getMessage());
    } catch (IOException unreadable) {
      throw new VoiceTurnRefusedException(
          HttpStatus.BAD_REQUEST, "invalid-voice-turn", "the audio part could not be read");
    }
  }

  private static void send(SseEmitter emitter, VoiceTurnEvent event) {
    try {
      emitter.send(SseEmitter.event().name(event.name()).data(event, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException gone) {
      // The caller went away; the turn still ends and settles what it spent.
      logger.debug("Voice turn client gone before {}", event.name());
    }
  }
}
