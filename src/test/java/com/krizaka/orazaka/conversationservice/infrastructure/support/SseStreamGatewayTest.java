package com.krizaka.orazaka.conversationservice.infrastructure.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

class SseStreamGatewayTest {

  private final SseStreamGateway gateway = new SseStreamGateway();

  @Test
  void createEmitter_createsWithZeroTimeout() {
    SseEmitter emitter = gateway.createEmitter();
    assertNotNull(emitter);
    assertEquals(0L, emitter.getTimeout());
  }

  @Test
  void subscribe_happyPath_sendsAndCompletes() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    Flux<String> stream = Flux.just("item1", "item2");

    gateway.subscribe(emitter, stream, "trace-1");

    verify(emitter).send("item1");
    verify(emitter).send("item2");
    verify(emitter).complete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void subscribe_sendThrows_cleansUp() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(new IOException("Connection lost")).when(emitter).send(any(Object.class));
    Flux<String> stream = Flux.concat(Flux.just("item1"), Flux.never());

    gateway.subscribe(emitter, stream, "trace-2");

    verify(emitter).send("item1");
    // Verify cleanup was registered and triggered
    ArgumentCaptor<Runnable> cleanupCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(emitter).onCompletion(cleanupCaptor.capture());

    // Emitter should not have completed normally because send failed
    verify(emitter, never()).complete();
  }

  @Test
  void subscribe_streamError_completesWithError() {
    SseEmitter emitter = mock(SseEmitter.class);
    Throwable err = new RuntimeException("API error");
    Flux<String> stream = Flux.error(err);

    gateway.subscribe(emitter, stream, "trace-3");

    verify(emitter).completeWithError(err);
  }

  @Test
  @SuppressWarnings("unchecked")
  void subscribe_callbacks_triggerCleanup() {
    SseEmitter emitter = mock(SseEmitter.class);
    Flux<String> stream = Flux.never();

    gateway.subscribe(emitter, stream, "trace-4");

    ArgumentCaptor<Runnable> onCompletionCaptor = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Runnable> onTimeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Consumer<Throwable>> onErrorCaptor = ArgumentCaptor.forClass(Consumer.class);

    verify(emitter).onCompletion(onCompletionCaptor.capture());
    verify(emitter).onTimeout(onTimeoutCaptor.capture());
    verify(emitter).onError(onErrorCaptor.capture());

    // Execute runnables/consumers to ensure no exceptions are thrown and cleanup code is visited
    assertDoesNotThrow(() -> onCompletionCaptor.getValue().run());
    assertDoesNotThrow(() -> onTimeoutCaptor.getValue().run());
    assertDoesNotThrow(() -> onErrorCaptor.getValue().accept(new RuntimeException()));
  }

  @Test
  void subscribe_completedEmitterAlreadyClosed_ignoredGracefully() {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(new IllegalStateException("Already closed")).when(emitter).complete();
    Flux<String> stream = Flux.empty();

    assertDoesNotThrow(() -> gateway.subscribe(emitter, stream, "trace-5"));
  }

  @Test
  void subscribe_errorEmitterAlreadyClosed_ignoredGracefully() {
    SseEmitter emitter = mock(SseEmitter.class);
    Throwable err = new RuntimeException("API error");
    doThrow(new IllegalStateException("Already closed")).when(emitter).completeWithError(err);
    Flux<String> stream = Flux.error(err);

    assertDoesNotThrow(() -> gateway.subscribe(emitter, stream, "trace-6"));
  }
}
