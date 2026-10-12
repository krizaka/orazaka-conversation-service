package com.krizaka.orazaka.conversationservice.infrastructure.support;

import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceClip;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Holds a voice turn's clips for the few minutes a client needs to play them, behind signed URLs
 * (ADR-076).
 *
 * <p><b>In memory, never on disk.</b> A clip is a sentence of a conversation that may be health
 * data (a REGULATED pack's turn); writing it to the uploads directory would give it a life — a
 * file, a backup — that nothing purges by data class. It lives {@link #TTL} and is gone, and a
 * restart forgets every clip, which is the property and not a defect.
 *
 * <p><b>Signed, so the player needs no token.</b> An {@code <audio>} element cannot carry a bearer
 * header. The URL carries an expiry and an HMAC over the clip id and that expiry, with a key drawn
 * at start-up and never written anywhere: a URL is valid on this process, for five minutes, and for
 * nothing else. The clip id is a random UUID, so the signature guards the expiry rather than
 * secrecy of the id alone.
 */
@Component
public class VoiceClipStore {

  /** How long a clip can be fetched — the {@code sentence.audio.url} contract. */
  public static final Duration TTL = Duration.ofSeconds(300);

  /** Where clips are served; the controller maps it. */
  public static final String PATH = "/api/v1/voice/clips/";

  private static final String HMAC = "HmacSHA256";

  private final Map<UUID, Stored> clips = new ConcurrentHashMap<>();
  private final byte[] key;
  private final Clock clock;

  /** Draws a fresh signing key: no URL survives a restart, because no clip does. */
  public VoiceClipStore() {
    this(Clock.systemUTC(), randomKey());
  }

  VoiceClipStore(Clock clock, byte[] key) {
    this.clock = clock;
    this.key = key.clone();
  }

  /**
   * Keeps a clip and returns the signed path that fetches it.
   *
   * @param clip the synthesised audio
   * @return a path like {@code /api/v1/voice/clips/{id}?expires=…&signature=…}
   */
  public String keep(VoiceClip clip) {
    evictExpired();
    UUID id = UUID.randomUUID();
    long expires = clock.instant().plus(TTL).getEpochSecond();
    clips.put(id, new Stored(clip, expires));
    return PATH + id + "?expires=" + expires + "&signature=" + sign(id, expires);
  }

  /**
   * The clip a signed path names, when the signature holds and it has not expired.
   *
   * @param id the clip
   * @param expires the expiry the URL carries
   * @param signature the URL's signature
   * @return the clip, or empty — the same answer for a forged, an expired and an unknown URL
   */
  public Optional<VoiceClip> fetch(UUID id, long expires, String signature) {
    if (signature == null
        || clock.instant().getEpochSecond() > expires
        || !MessageDigest.isEqual(
            sign(id, expires).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII))) {
      return Optional.empty();
    }
    Stored stored = clips.get(id);
    return stored == null || stored.expires() != expires
        ? Optional.empty()
        : Optional.of(stored.clip());
  }

  private void evictExpired() {
    long now = clock.instant().getEpochSecond();
    clips.entrySet().removeIf(entry -> entry.getValue().expires() < now);
  }

  private String sign(UUID id, long expires) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key, HMAC));
      return HexFormat.of()
          .formatHex(mac.doFinal((id + ":" + expires).getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", unavailable);
    }
  }

  private static byte[] randomKey() {
    byte[] drawn = new byte[32];
    new SecureRandom().nextBytes(drawn);
    return drawn;
  }

  /** How many clips are held — for the test of eviction. */
  int size() {
    return clips.size();
  }

  /** The current instant, through the store's clock. */
  Instant now() {
    return clock.instant();
  }

  private record Stored(VoiceClip clip, long expires) {}
}
