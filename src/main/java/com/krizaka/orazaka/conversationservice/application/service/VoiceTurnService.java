package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.ConsumptionReport;
import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.billing.domain.port.CreditAuthorizationClient;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.conversationservice.domain.model.voice.PreparedTurn;
import com.krizaka.orazaka.conversationservice.domain.model.voice.SentenceBuffer;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceClip;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceTurn;
import com.krizaka.orazaka.conversationservice.domain.model.voice.VoiceTurnEvent;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceClipStore;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceTurnRefusedException;
import com.krizaka.orazaka.conversationservice.infrastructure.support.VoiceTurnUsageRegistry;
import com.krizaka.orazaka.core.application.pipeline.PipelineShortCircuitException;
import com.krizaka.orazaka.core.application.processing.AudioPreProcessor;
import com.krizaka.orazaka.core.application.processing.ProcessedAudioPayload;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.audio.AudioRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest.ChatMessage;
import com.krizaka.orazaka.core.domain.model.chat.ChatResponse;
import com.krizaka.orazaka.core.domain.model.chat.TokenUsage;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.persistence.domain.model.SpeechVoice;
import com.krizaka.orazaka.persistence.domain.ports.inbound.SpeechVoiceManager;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import com.krizaka.users.domain.model.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A conversational turn taken by voice: hear, answer through the engine's own pipeline, speak each
 * sentence as soon as it is written (ADR-076).
 *
 * <p><b>Where it lives, and why.</b> A voice turn is a chat turn with an ear and a mouth, so it
 * lives where chat lives — synchronous, off the broker (AGENTS.md §6), through the same {@link
 * AiClient#stream} every chat turn takes. That is what keeps "one door" (ADR-068, ADR-070) true:
 * the model is reached through the interceptor pipeline, so the crisis guard, the scope guard, the
 * kill-switches and the entitlement check run on it exactly as on a typed turn. Transcription and
 * synthesis are synchronous inference calls on the native runtime that already serves them (Whisper
 * and Piper on LocalAI), like the chat model on Ollama — not jobs. A job would add the outbox
 * relay's 500 ms and the job plane's round trip to every sentence, and a person waiting for a voice
 * hears every one of them.
 *
 * <p><b>The pack is the law of the turn.</b> A turn taken under an installation carries what that
 * installation's pack declares — its guards, the region's crisis line, its system prompt — read
 * from the studio service per turn. The pack's prompt is the first system message; a caller's
 * context comes after it, never before. The guards judge what the person said (the declared
 * subject), not the context or the prompt around it.
 *
 * <p><b>One metering entry per turn.</b> One hold before anything is spent; one aggregate
 * settlement after, pricing transcription in audio minutes, the answer in tokens and the speech in
 * characters against their own rows (ADR-044/045). The chat leg is marked deferred so the
 * pipeline's gate checks entitlement and takes no second hold. A turn a guard answered is released,
 * not billed: a refusal is free (ADR-051 §3), and nobody is charged for being given a crisis line.
 */
@Service
public class VoiceTurnService {

  private static final Logger logger = LoggerFactory.getLogger(VoiceTurnService.class);

  /** What speech and transcription are entitled by — the plan grant, not a Studio. */
  static final String AUDIO_ENTITLEMENT = "capability.audio";

  /** The catalogue's transcription model: what runs, and what the minute is priced on. */
  static final String TRANSCRIPTION_MODEL = "whisper-base";

  private static final String CRISIS_GUARD = "SafetyInterceptor";
  private static final String SCOPE_GUARD = "ScopeGuardInterceptor";
  private static final Duration CHAT_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration USAGE_WAIT = Duration.ofSeconds(2);

  private final AiClient aiClient;
  private final AudioPreProcessor transcriber;
  private final ContextService contextService;
  private final TurnDeclarationClient declarations;
  private final SpeechVoiceManager voices;
  private final EntitlementProvider entitlements;
  private final CreditAuthorizationClient credits;
  private final VoiceClipStore clips;
  private final VoiceTurnUsageRegistry usage;

  /**
   * @param aiClient the engine: the chat stream and speech synthesis
   * @param transcriber the engine's transcription
   * @param contextService builds the turn's context from the actor
   * @param declarations the studio service's answer about an installation's pack
   * @param voices the catalogue's voices, by declared locale
   * @param entitlements the actor's plan
   * @param credits the hold and the settlement
   * @param clips where synthesised sentences wait to be fetched
   * @param usage where the chat stream's token count arrives
   */
  public VoiceTurnService(
      AiClient aiClient,
      AudioPreProcessor transcriber,
      ContextService contextService,
      TurnDeclarationClient declarations,
      SpeechVoiceManager voices,
      EntitlementProvider entitlements,
      CreditAuthorizationClient credits,
      VoiceClipStore clips,
      VoiceTurnUsageRegistry usage) {
    this.aiClient = Objects.requireNonNull(aiClient, "AiClient required");
    this.transcriber = Objects.requireNonNull(transcriber, "AudioPreProcessor required");
    this.contextService = Objects.requireNonNull(contextService, "ContextService required");
    this.declarations = Objects.requireNonNull(declarations, "TurnDeclarationClient required");
    this.voices = Objects.requireNonNull(voices, "SpeechVoiceManager required");
    this.entitlements = Objects.requireNonNull(entitlements, "EntitlementProvider required");
    this.credits = Objects.requireNonNull(credits, "CreditAuthorizationClient required");
    this.clips = Objects.requireNonNull(clips, "VoiceClipStore required");
    this.usage = Objects.requireNonNull(usage, "VoiceTurnUsageRegistry required");
  }

  /**
   * What a turn needs before it may start — everything that can refuse it with an HTTP status, so a
   * refusal is a status and never half a stream.
   *
   * @param turn the request
   * @param actor the person taking the turn
   * @return the prepared turn, holding its credit reservation
   * @throws VoiceTurnRefusedException 404 for an unknown installation, 403 without the audio grant,
   *     422 when no voice speaks the locale
   * @throws com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException 451, relayed
   * @throws com.krizaka.billing.domain.exception.InsufficientCreditsException 402
   */
  public PreparedTurn prepare(VoiceTurn turn, User actor) {
    Objects.requireNonNull(turn, "turn required");
    String actorId = actor.id().toString();
    TurnDeclarations declared =
        turn.installationId() == null
            ? null
            : declarations
                .forInstallation(turn.installationId(), actorId)
                .orElseThrow(
                    () ->
                        new VoiceTurnRefusedException(
                            HttpStatus.NOT_FOUND,
                            "installation-not-found",
                            "no active installation " + turn.installationId() + " of yours"));

    EntitlementSnapshot plan = entitlements.forActor(actorId);
    if (plan.resolved() && !plan.allows(AUDIO_ENTITLEMENT)) {
      throw new VoiceTurnRefusedException(
          HttpStatus.FORBIDDEN,
          "capability-not-in-plan",
          "plan " + plan.planKey() + " does not include speech (" + AUDIO_ENTITLEMENT + ")");
    }

    SpeechVoice voice =
        (turn.voice() == null ? voices.defaultVoice(turn.locale()) : voices.findVoice(turn.voice()))
            .orElseThrow(
                () ->
                    new VoiceTurnRefusedException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "no-voice",
                        turn.voice() == null
                            ? "no voice speaks " + turn.locale() + " in the catalogue"
                            : "no catalogued voice named " + turn.voice()));

    Context base = contextService.resolve(actor, turn.conversationId());
    String turnId = UUID.randomUUID().toString();
    String holdId = hold(actorId, turn.conversationId());
    return new PreparedTurn(turn, declared, voice, base, turnId, holdId, System.nanoTime());
  }

  /**
   * Runs a prepared turn to its end, emitting each event as it happens. Never throws: a failure
   * after the stream opened is an {@code error} event, and the hold is released.
   *
   * @param prepared what {@link #prepare} returned
   * @param sink where events go, in order; called from more than one thread, one call at a time
   */
  public void run(PreparedTurn prepared, Consumer<VoiceTurnEvent> sink) {
    Consumer<VoiceTurnEvent> emit = serialised(sink);
    Metering metering = new Metering();
    long t0 = prepared.startedNanos();
    try {
      String said = prepared.turn().text();
      if (prepared.turn().audio() != null) {
        long sttStart = System.nanoTime();
        ProcessedAudioPayload heard =
            transcriber.process(prepared.turn().audio(), TRANSCRIPTION_MODEL);
        metering.sttMs = millisSince(sttStart);
        metering.sttSeconds = heard.durationSeconds();
        said = heard.transcript().strip();
        emit.accept(
            new VoiceTurnEvent.Transcript(
                said,
                heard.durationSeconds() == null ? null : heard.durationSeconds().doubleValue()));
      }
      if (said == null || said.isBlank()) {
        // Nothing was heard: no answer is generated for silence. What was transcribed is still
        // billed, because it ran.
        settle(prepared, metering, TokenUsage.none());
        emit.accept(done(metering, TokenUsage.none(), t0));
        return;
      }
      answer(prepared, said, metering, emit, t0);
    } catch (RuntimeException failed) {
      usage.forget(prepared.turnId());
      release(prepared.holdId(), "voice turn failed: " + failed.getClass().getSimpleName());
      // The class, never the message: an engine message can quote the prompt (ADR-064).
      logger.warn("Voice turn failed ({})", failed.getClass().getSimpleName());
      emit.accept(new VoiceTurnEvent.Failed("turn-failed", "the turn could not be completed"));
    }
  }

  private void answer(
      PreparedTurn prepared,
      String said,
      Metering metering,
      Consumer<VoiceTurnEvent> emit,
      long t0) {
    Speaker speaker = new Speaker(prepared.voice(), prepared.base(), metering, emit, t0);
    Thread speaking = Thread.ofVirtual().name("voice-speaker-" + prepared.turnId()).start(speaker);
    SentenceBuffer buffer = new SentenceBuffer();
    usage.expect(prepared.turnId());
    try {
      for (ChatResponse chunk :
          aiClient.stream(chatRequest(prepared, said)).timeout(CHAT_TIMEOUT).toIterable()) {
        String text = chunk.content();
        if (text == null || text.isEmpty()) {
          continue;
        }
        if (metering.firstDeltaMs == null) {
          metering.firstDeltaMs = millisSince(t0);
        }
        emit.accept(new VoiceTurnEvent.Delta(text));
        buffer.accept(text).forEach(speaker::say);
      }
      buffer.flush().ifPresent(speaker::say);
      speaker.finish();
      join(speaking);
      TokenUsage tokens = usage.await(prepared.turnId(), USAGE_WAIT);
      settle(prepared, metering, tokens);
      emit.accept(done(metering, tokens, t0));
      logger.info(
          "Voice turn done: stt={}ms firstDelta={}ms firstSentence={}ms total={}ms sentences={}",
          metering.sttMs,
          metering.firstDeltaMs,
          metering.firstSentenceMs,
          millisSince(t0),
          metering.sentences.get());
    } catch (PipelineShortCircuitException refusal) {
      speaker.finish();
      join(speaking);
      usage.forget(prepared.turnId());
      answerRefusal(prepared, refusal, metering, emit, t0);
    } catch (RuntimeException failed) {
      speaker.finish();
      join(speaking);
      throw failed;
    }
  }

  /**
   * A guard answered. The reply is the pack's text, spoken as written — never generated, never
   * reformulated — and the turn is not billed.
   */
  private void answerRefusal(
      PreparedTurn prepared,
      PipelineShortCircuitException refusal,
      Metering metering,
      Consumer<VoiceTurnEvent> emit,
      long t0) {
    TurnDeclarations declared = prepared.declarations();
    String spoken = refusal.getMessage();
    if (CRISIS_GUARD.equals(refusal.interceptorId())
        && declared != null
        && declared.guardsCrisis()) {
      VoiceTurnEvent.Audio audio = speak(prepared.voice(), prepared.base(), spoken, metering);
      emit.accept(
          new VoiceTurnEvent.SafetyCrisis(
              declared.crisisResponse(), declared.crisisResource(), spoken, audio));
    } else if (SCOPE_GUARD.equals(refusal.interceptorId())) {
      VoiceTurnEvent.Audio audio = speak(prepared.voice(), prepared.base(), spoken, metering);
      emit.accept(new VoiceTurnEvent.ScopeRefused(spoken, audio));
    } else {
      // Not a guard: a plan or a balance refused the chat leg. The turn stops; nothing is spoken.
      release(prepared.holdId(), "refused by " + refusal.interceptorId());
      emit.accept(new VoiceTurnEvent.Failed(refusal.reason(), "the turn was refused"));
      return;
    }
    release(prepared.holdId(), "answered by " + refusal.interceptorId());
    if (metering.firstSentenceMs == null) {
      metering.firstSentenceMs = millisSince(t0);
    }
    logger.info("Voice turn answered by {} ({})", refusal.interceptorId(), refusal.reason());
    emit.accept(done(metering, TokenUsage.none(), t0));
  }

  /**
   * The chat leg: the pack's system prompt first, the caller's context after it, then what the
   * person said — with the pack's guards and the declared subject on the context.
   */
  private ChatRequest chatRequest(PreparedTurn prepared, String said) {
    List<ChatMessage> messages = new ArrayList<>();
    TurnDeclarations declared = prepared.declarations();
    if (declared != null && declared.systemPrompt() != null) {
      messages.add(new ChatMessage("system", declared.systemPrompt()));
    }
    if (prepared.turn().context() != null) {
      messages.add(new ChatMessage("system", prepared.turn().context()));
    }

    Map<String, Object> platform = new HashMap<>(prepared.base().preferences());
    platform.put(VoiceTurnUsageRegistry.TURN_KEY, prepared.turnId());
    // Metered here, once, for the whole turn: the pipeline's gate checks the plan and takes no
    // second hold (ADR-044).
    platform.put(JobCommand.DEFERRED_METERING_KEY, Boolean.TRUE);
    // What the guards judge is what the person said — not the context, not the pack's prompt,
    // whose own words ("aucun diagnostic") would make the pack refuse itself (ADR-055 §7).
    platform.put(JobCommand.GUARD_SUBJECT_KEY, said);
    if (declared != null) {
      if (declared.scopeGuard() != null) {
        platform.put(
            JobCommand.SCOPE_REFUSED_TERMS_KEY,
            String.join(",", declared.scopeGuard().refusedTerms()));
        platform.put(JobCommand.SCOPE_REFUSAL_KEY, declared.scopeGuard().refusal());
      }
      if (declared.guardsCrisis()) {
        platform.put(JobCommand.SAFETY_CRISIS_TERMS_KEY, String.join(",", declared.crisisTerms()));
        platform.put(JobCommand.SAFETY_RESPONSE_KEY, declared.crisisReply());
      }
    }
    Context context =
        new Context(
            prepared.base().userId(),
            prepared.base().conversationId(),
            platform,
            prepared.base().authorities());
    return new ChatRequest(said, messages, Map.of(), context);
  }

  private VoiceTurnEvent.Audio speak(
      SpeechVoice voice, Context base, String text, Metering metering) {
    VoiceClip clip =
        VoiceClip.of(
            aiClient
                .audio(
                    new AudioRequest(
                        text,
                        voice.modelName(),
                        voice.modelName(),
                        // WAV: its duration is read from its header, so ttsSeconds is measured.
                        Map.of(AudioRequest.RESPONSE_FORMAT, "wav"),
                        base))
                .audioData());
    metering.ttsCharacters.addAndGet(text.length());
    if (clip.durationMs() != null) {
      metering.ttsMillis.addAndGet(clip.durationMs());
    }
    return new VoiceTurnEvent.Audio(
        clips.keep(clip), clip.durationMs(), clip.contentType(), voice.modelName(), voice.locale());
  }

  private String hold(String actorId, String conversationId) {
    CreditHoldResponse hold =
        credits.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.CHAT, null, conversationId, null, 0L));
    return hold.metered() ? hold.holdId() : null;
  }

  /** One settlement for the whole turn: every leg priced against its own row. */
  private void settle(PreparedTurn prepared, Metering metering, TokenUsage tokens) {
    if (prepared.holdId() == null) {
      return;
    }
    List<MeteredStep> steps =
        meteredSteps(prepared.voice(), metering.sttSeconds, tokens, metering.ttsCharacters.get());
    credits.settleAggregate(prepared.holdId(), steps, "voice:" + prepared.holdId());
  }

  /**
   * The turn's legs as the ledger prices them. A leg that measured nothing is absent, never zero:
   * an unmeasured leg contributes nothing rather than a guess (ADR-041).
   */
  static List<MeteredStep> meteredSteps(
      SpeechVoice voice, BigDecimal sttSeconds, TokenUsage tokens, long ttsCharacters) {
    List<MeteredStep> steps = new ArrayList<>();
    if (sttSeconds != null) {
      steps.add(
          new MeteredStep(
              BillableCapability.AUDIO, TRANSCRIPTION_MODEL, report(null, sttSeconds, null)));
    }
    if (tokens.reported()) {
      steps.add(
          new MeteredStep(
              BillableCapability.CHAT, null, report(null, null, (long) tokens.totalTokens())));
    }
    if (ttsCharacters > 0) {
      steps.add(
          new MeteredStep(
              BillableCapability.AUDIO, voice.modelName(), report(ttsCharacters, null, null)));
    }
    return steps;
  }

  private static ConsumptionReport report(Long characters, BigDecimal audioSeconds, Long tokens) {
    return new ConsumptionReport(
        null, null, null, null, null, null, null, characters, audioSeconds, tokens, null);
  }

  private void release(String holdId, String reason) {
    if (holdId != null) {
      credits.release(holdId, reason);
    }
  }

  private static VoiceTurnEvent.Done done(Metering metering, TokenUsage tokens, long t0) {
    return new VoiceTurnEvent.Done(
        new VoiceTurnEvent.Usage(
            metering.sttSeconds == null ? null : metering.sttSeconds.doubleValue(),
            tokens.reported() ? tokens.totalTokens() : null,
            metering.ttsMillis.get() == 0 ? null : metering.ttsMillis.get() / 1000.0,
            metering.ttsCharacters.get()),
        new VoiceTurnEvent.Timings(
            metering.sttMs, metering.firstDeltaMs, metering.firstSentenceMs, millisSince(t0)));
  }

  private static Consumer<VoiceTurnEvent> serialised(Consumer<VoiceTurnEvent> sink) {
    Object lock = new Object();
    return event -> {
      synchronized (lock) {
        sink.accept(event);
      }
    };
  }

  private static void join(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static long millisSince(long nanos) {
    return (System.nanoTime() - nanos) / 1_000_000;
  }

  /**
   * Speaks sentences in the order they were written, one at a time, on its own thread — so the
   * model keeps writing sentence three while sentence two is being synthesised. One at a time
   * because Piper serves one synthesis at a time: two in flight would queue in the engine and gain
   * nothing.
   */
  private final class Speaker implements Runnable {

    private static final String END = "\u0000end";

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final SpeechVoice voice;
    private final Context base;
    private final Metering metering;
    private final Consumer<VoiceTurnEvent> emit;
    private final long t0;

    Speaker(
        SpeechVoice voice,
        Context base,
        Metering metering,
        Consumer<VoiceTurnEvent> emit,
        long t0) {
      this.voice = voice;
      this.base = base;
      this.metering = metering;
      this.emit = emit;
      this.t0 = t0;
    }

    void say(String sentence) {
      queue.add(sentence);
    }

    void finish() {
      queue.add(END);
    }

    @Override
    public void run() {
      try {
        for (String sentence = queue.take(); !END.equals(sentence); sentence = queue.take()) {
          VoiceTurnEvent.Audio audio = speak(voice, base, sentence, metering);
          if (metering.firstSentenceMs == null) {
            metering.firstSentenceMs = millisSince(t0);
          }
          emit.accept(
              new VoiceTurnEvent.Sentence(metering.sentences.getAndIncrement(), sentence, audio));
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException failed) {
        logger.warn("Speech synthesis failed ({})", failed.getClass().getSimpleName());
        emit.accept(new VoiceTurnEvent.Failed("speech-failed", "a sentence could not be spoken"));
        queue.clear();
      }
    }
  }

  /** What a turn measured, written by two threads and read once at the end. */
  private static final class Metering {
    volatile Long sttMs;
    volatile BigDecimal sttSeconds;
    volatile Long firstDeltaMs;
    volatile Long firstSentenceMs;
    final AtomicLong ttsCharacters = new AtomicLong();
    final AtomicLong ttsMillis = new AtomicLong();
    final AtomicInteger sentences = new AtomicInteger();
  }
}
