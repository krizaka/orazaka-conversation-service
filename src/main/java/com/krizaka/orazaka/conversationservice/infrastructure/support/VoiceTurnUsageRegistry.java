package com.krizaka.orazaka.conversationservice.infrastructure.support;

import com.krizaka.orazaka.core.domain.event.ChatCompletedEvent;
import com.krizaka.orazaka.core.domain.model.chat.TokenUsage;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hands a voice turn the tokens its chat stream consumed (ADR-076).
 *
 * <p>The engine reports a stream's usage once, in the {@link ChatCompletedEvent} it publishes when
 * the stream completes — the same event {@code ChatSettlementListener} settles a chat turn from.
 * The voice turn declares its own id on the turn's context, and this registry matches the event to
 * it: declared, not inferred from a conversation id that two turns can share.
 */
@Component
public class VoiceTurnUsageRegistry {

  /** The platform key a voice turn stamps on its chat context. */
  public static final String TURN_KEY = "orazaka.pipeline.voiceTurn";

  private final Map<String, CompletableFuture<TokenUsage>> pending = new ConcurrentHashMap<>();

  /**
   * Starts waiting for one turn's usage.
   *
   * @param turnId the id the turn declared on its context
   */
  public void expect(String turnId) {
    pending.put(turnId, new CompletableFuture<>());
  }

  /**
   * The usage the turn's stream reported, waiting at most {@code wait} for the event.
   *
   * @param turnId the turn
   * @param wait how long to wait
   * @return the usage, or {@link TokenUsage#none()} when none arrived — unmeasured, never zero
   */
  public TokenUsage await(String turnId, Duration wait) {
    CompletableFuture<TokenUsage> future = pending.remove(turnId);
    if (future == null) {
      return TokenUsage.none();
    }
    try {
      return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException late) {
      return TokenUsage.none();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return TokenUsage.none();
    }
  }

  /**
   * Stops waiting for a turn that ended without a completed stream.
   *
   * @param turnId the turn
   */
  public void forget(String turnId) {
    pending.remove(turnId);
  }

  /**
   * Completes the matching turn's wait.
   *
   * @param event a completed chat stream
   */
  @EventListener
  public void onChatCompleted(ChatCompletedEvent event) {
    Object turnId = event.request().context().preferences().get(TURN_KEY);
    if (turnId == null) {
      return;
    }
    CompletableFuture<TokenUsage> future = pending.get(String.valueOf(turnId));
    if (future != null) {
      future.complete(event.response().tokenUsage());
    }
  }
}
