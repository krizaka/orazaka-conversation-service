package com.krizaka.orazaka.conversationservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceClip;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceClipStoreTest {

  private static final Pattern URL =
      Pattern.compile(
          "^/api/v1/voice/clips/([0-9a-f-]{36})\\?expires=(\\d+)&signature=([0-9a-f]{64})$");
  private static final VoiceClip CLIP = VoiceClip.of(new byte[] {'I', 'D', '3', 1});

  /** A clock the test moves. */
  private static final class MovingClock extends Clock {
    final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-10-12T00:00:00Z"));

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @Test
  @DisplayName("A kept clip is fetched by its signed URL for five minutes, and not after")
  void signedAndExpiring() {
    MovingClock clock = new MovingClock();
    VoiceClipStore store = new VoiceClipStore(clock, new byte[32]);

    Matcher url = URL.matcher(store.keep(CLIP));
    assertThat(url.matches()).isTrue();
    UUID id = UUID.fromString(url.group(1));
    long expires = Long.parseLong(url.group(2));

    assertThat(store.fetch(id, expires, url.group(3))).contains(CLIP);
    clock.now.set(clock.now.get().plus(VoiceClipStore.TTL).plusSeconds(1));
    assertThat(store.fetch(id, expires, url.group(3))).isEmpty();
  }

  @Test
  @DisplayName("A forged signature or a stretched expiry fetches nothing")
  void forgeryFails() {
    VoiceClipStore store = new VoiceClipStore(new MovingClock(), new byte[32]);
    Matcher url = URL.matcher(store.keep(CLIP));
    assertThat(url.matches()).isTrue();
    UUID id = UUID.fromString(url.group(1));
    long expires = Long.parseLong(url.group(2));

    assertThat(store.fetch(id, expires, "0".repeat(64))).isEmpty();
    assertThat(store.fetch(id, expires + 3600, url.group(3))).isEmpty();
    assertThat(store.fetch(id, expires, null)).isEmpty();
    assertThat(store.fetch(UUID.randomUUID(), expires, url.group(3))).isEmpty();
  }

  @Test
  @DisplayName("Another process's key signs nothing here")
  void keyIsPerProcess() {
    VoiceClipStore mine = new VoiceClipStore(new MovingClock(), new byte[32]);
    byte[] other = new byte[32];
    other[0] = 1;
    VoiceClipStore theirs = new VoiceClipStore(new MovingClock(), other);
    Matcher url = URL.matcher(theirs.keep(CLIP));
    assertThat(url.matches()).isTrue();

    assertThat(
            mine.fetch(UUID.fromString(url.group(1)), Long.parseLong(url.group(2)), url.group(3)))
        .isEmpty();
  }

  @Test
  @DisplayName("Expired clips leave memory when the next one arrives")
  void evicts() {
    MovingClock clock = new MovingClock();
    VoiceClipStore store = new VoiceClipStore(clock, new byte[32]);
    store.keep(CLIP);
    clock.now.set(clock.now.get().plus(VoiceClipStore.TTL).plusSeconds(1));
    store.keep(CLIP);

    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("The production constructor draws its own key")
  void defaultConstructor() {
    VoiceClipStore store = new VoiceClipStore();

    assertThat(URL.matcher(store.keep(CLIP)).matches()).isTrue();
    assertThat(store.now()).isNotNull();
  }
}
