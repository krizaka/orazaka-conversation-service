package com.krizaka.orazaka.conversationservice.infrastructure.support;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Injectable SSE lifecycle gateway: creates emitters and pipes reactive streams through them.
 *
 * <p>Replaces the former static {@code SseStreamHelper}: SSE orchestration is an injectable
 * {@code @Component} rather than a static utility [ERR-127], giving controllers a mockable seam.
 *
 * <p><b>Thread Safety</b>: uses {@link AtomicReference} for the Reactor subscription reference,
 * safe for concurrent disposal from Virtual Threads.
 */
@Component
public class SseStreamGateway {

  private static final Logger logger = LoggerFactory.getLogger(SseStreamGateway.class);

  /**
   * Creates a new {@link SseEmitter} with infinite timeout.
   *
   * @return a pre-configured emitter ready for streaming
   */
  public SseEmitter createEmitter() {
    return new SseEmitter(0L);
  }

  /**
   * Subscribes to a reactive stream and pipes each element through the SSE emitter.
   *
   * <ul>
   *   <li>Success: sends each element via {@code emitter.send()}, completes when the Flux finishes
   *   <li>Error: logs the error and completes the emitter with error
   *   <li>Cleanup: disposes the Reactor subscription on emitter timeout, completion, or error
   * </ul>
   *
   * @param <T> the payload type emitted by the Flux
   * @param emitter the SSE emitter to pipe data through
   * @param stream the reactive stream to subscribe to
   * @param traceId a human-readable identifier for logging (e.g. conversationId)
   */
  public <T> void subscribe(SseEmitter emitter, Flux<T> stream, String traceId) {
    AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

    Runnable cleanup =
        () -> {
          Disposable sub = subscriptionRef.get();
          if (sub != null) {
            sub.dispose();
          }
        };

    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(ex -> cleanup.run());

    Disposable subscription =
        stream
            .doFinally(signal -> cleanup.run())
            .subscribe(
                response -> {
                  try {
                    emitter.send(response);
                  } catch (Exception e) {
                    logger.error("Failed to send SSE chunk [{}]", traceId, e);
                    cleanup.run();
                  }
                },
                error -> {
                  logger.error("SSE stream error [{}]", traceId, error);
                  try {
                    emitter.completeWithError(error);
                  } catch (Exception ignored) {
                    logger.trace("Emitter already closed during error completion [{}]", traceId);
                  }
                },
                () -> {
                  logger.info("SSE stream completed [{}]", traceId);
                  try {
                    emitter.complete();
                  } catch (Exception ignored) {
                    logger.trace("Emitter already closed during normal completion [{}]", traceId);
                  }
                });

    subscriptionRef.set(subscription);
  }
}
