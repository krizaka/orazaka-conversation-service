package com.krizaka.orazaka.conversationservice.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed-window limit on the checks one actor may ask for: every executed check is a paid call to
 * a third party, so the door is slower than the rest of the API. The window is one minute.
 */
public class CheckRateLimitService {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private record Window(Instant start, int used) {}

  private final int perMinute;
  private final Clock clock;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  /**
   * @param perMinute calls one actor may make in a minute
   * @param clock the clock
   */
  public CheckRateLimitService(int perMinute, Clock clock) {
    if (perMinute <= 0) {
      throw new IllegalArgumentException("the checks rate limit must be positive");
    }
    this.perMinute = perMinute;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Takes one call of the actor's window.
   *
   * @param actor the actor's key (tenant and subject)
   * @return {@code 0} when the call may go on, else the seconds to wait
   */
  public long tryAcquire(String actor) {
    Instant now = clock.instant();
    long[] wait = {0};
    windows.compute(
        actor,
        (key, window) -> {
          Window current =
              window == null || !now.isBefore(window.start().plus(WINDOW))
                  ? new Window(now, 0)
                  : window;
          if (current.used() >= perMinute) {
            wait[0] = Math.max(1, Duration.between(now, current.start().plus(WINDOW)).toSeconds());
            return current;
          }
          return new Window(current.start(), current.used() + 1);
        });
    return wait[0];
  }
}
