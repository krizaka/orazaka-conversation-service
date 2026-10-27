package com.krizaka.orazaka.conversationservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CheckRateLimitServiceTest {

  /** A clock the test moves. */
  private static final class Moving extends Clock {
    private Instant now = Instant.parse("2026-10-27T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  @DisplayName("the call over the limit waits for the end of the window; the window then reopens")
  void windowFillsThenReopens() {
    Moving clock = new Moving();
    CheckRateLimitService limiter = new CheckRateLimitService(2, clock);

    assertThat(limiter.tryAcquire("acme/a")).isZero();
    assertThat(limiter.tryAcquire("acme/a")).isZero();
    clock.advance(Duration.ofSeconds(20));
    assertThat(limiter.tryAcquire("acme/a")).isEqualTo(40);

    clock.advance(Duration.ofSeconds(40));
    assertThat(limiter.tryAcquire("acme/a")).isZero();
  }

  @Test
  @DisplayName("each actor has its own window")
  void actorsAreIndependent() {
    CheckRateLimitService limiter = new CheckRateLimitService(1, new Moving());

    assertThat(limiter.tryAcquire("acme/a")).isZero();
    assertThat(limiter.tryAcquire("acme/b")).isZero();
    assertThat(limiter.tryAcquire("acme/a")).isPositive();
  }

  @Test
  @DisplayName("a limit that is not positive is refused")
  void limitMustBePositive() {
    assertThatThrownBy(() -> new CheckRateLimitService(0, new Moving()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
