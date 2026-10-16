package com.krizaka.orazaka.conversationservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.billing.domain.port.CreditAuthorizationClient;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.conversationservice.domain.model.voice.PreparedTurn;
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
import com.krizaka.orazaka.core.domain.model.audio.AudioResponse;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatResponse;
import com.krizaka.orazaka.core.domain.model.chat.TokenUsage;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.persistence.domain.model.SpeechVoice;
import com.krizaka.orazaka.persistence.domain.ports.inbound.SpeechVoiceManager;
import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import com.krizaka.orazaka.studio.domain.model.PackScopeGuard;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import com.krizaka.users.domain.model.User;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * The voice turn's own decisions, against doubles of the engine and the platform (ADR-076): the
 * order of what it says, where the pack's prompt and the caller's context go, what the guards
 * judge, and the one settlement a turn makes. The live engine, guards included, is the e2e suite's.
 */
class VoiceTurnServiceTest {

  private static final UUID ACTOR = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");
  private static final UUID INSTALLATION = UUID.fromString("7a1c5d1e-0000-4000-8000-000000000001");
  private static final SpeechVoice SIWIS = new SpeechVoice("piper-fr-siwis-medium", "fr-FR");
  private static final CrisisResource QC =
      new CrisisResource(
          "CA-QC",
          "Ligne québécoise de prévention du suicide",
          "1 866 APPELLE",
          "24/7",
          "https://www.quebec.ca/",
          LocalDate.of(2026, 10, 11));
  private static final TurnDeclarations WELLBEING =
      new TurnDeclarations(
          INSTALLATION,
          "session-summary",
          "bien-etre",
          "CA-QC",
          new PackScopeGuard(List.of("posologie"), "Je ne parle pas de posologie."),
          List.of("en finir"),
          "Je m'arrête là.",
          QC,
          "Tu es une présence IA.");

  private AiClient ai;
  private AudioPreProcessor transcriber;
  private TurnDeclarationClient declarations;
  private SpeechVoiceManager voices;
  private EntitlementProvider entitlements;
  private CreditAuthorizationClient credits;
  private VoiceTurnService service;
  private User user;
  private final List<VoiceTurnEvent> events = new CopyOnWriteArrayList<>();

  /** A one-second, 16 kHz mono WAV: what the speech double answers. */
  private static byte[] wav() {
    int data = 32_000;
    ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes());
    b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16_000).putInt(32_000);
    b.putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(data);
    return b.array();
  }

  @BeforeEach
  void wire() {
    ai = mock(AiClient.class);
    transcriber = mock(AudioPreProcessor.class);
    declarations = mock(TurnDeclarationClient.class);
    voices = mock(SpeechVoiceManager.class);
    entitlements = mock(EntitlementProvider.class);
    credits = mock(CreditAuthorizationClient.class);
    ContextService contexts = mock(ContextService.class);
    user = mock(User.class);
    when(user.id()).thenReturn(ACTOR);
    when(contexts.resolve(any(), anyString()))
        .thenAnswer(
            call ->
                new Context(
                    ACTOR.toString(),
                    call.getArgument(1),
                    Map.of("preference.language", "fr"),
                    Set.of()));
    when(declarations.forInstallation(INSTALLATION, ACTOR.toString()))
        .thenReturn(Optional.of(WELLBEING));
    when(voices.defaultVoice("fr-CA")).thenReturn(Optional.of(SIWIS));
    when(entitlements.forActor(ACTOR.toString()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR.toString(),
                "premium",
                Map.of("capability.audio", "true"),
                1_000L,
                Instant.now().plusSeconds(60)));
    when(credits.hold(any()))
        .thenReturn(new CreditHoldResponse("hold-1", true, false, 20L, 980L, 2));
    when(ai.audio(any())).thenReturn(new AudioResponse(wav(), "wav"));
    service =
        new VoiceTurnService(
            ai,
            transcriber,
            contexts,
            declarations,
            voices,
            entitlements,
            credits,
            new VoiceClipStore(),
            new VoiceTurnUsageRegistry());
  }

  private VoiceTurn text(String said) {
    return new VoiceTurn("conv-1", "fr-CA", null, said, null, INSTALLATION, "Le fil : jeudi soir.");
  }

  private void answer(String... chunks) {
    when(ai.stream(any()))
        .thenReturn(
            Flux.fromArray(chunks).map(chunk -> new ChatResponse(chunk, "conv-1", Map.of())));
  }

  private List<String> names() {
    return events.stream().map(VoiceTurnEvent::name).toList();
  }

  @Test
  @DisplayName("A text turn: deltas as written, each sentence spoken in order, then done")
  void textTurn() {
    answer("Je t'écoute, ", "prends ton temps. ", "Qu'est-ce qui ", "a pesé le plus ?");

    service.run(service.prepare(text("La semaine a été lourde."), user), events::add);

    assertThat(names())
        .containsSubsequence("delta", "sentence", "sentence", "done")
        .doesNotContain("transcript", "error");
    List<VoiceTurnEvent.Sentence> sentences =
        events.stream()
            .filter(VoiceTurnEvent.Sentence.class::isInstance)
            .map(VoiceTurnEvent.Sentence.class::cast)
            .toList();
    assertThat(sentences)
        .extracting(VoiceTurnEvent.Sentence::text)
        .containsExactly("Je t'écoute, prends ton temps.", "Qu'est-ce qui a pesé le plus ?");
    assertThat(sentences.getFirst().audio().contentType()).isEqualTo("audio/wav");
    assertThat(sentences.getFirst().audio().durationMs()).isEqualTo(1_000L);
    assertThat(sentences.getFirst().audio().voice()).isEqualTo("piper-fr-siwis-medium");
    assertThat(sentences.getFirst().audio().url()).startsWith(VoiceClipStore.PATH);
    VoiceTurnEvent.Done done = (VoiceTurnEvent.Done) events.getLast();
    assertThat(done.usage().ttsSeconds()).isEqualTo(2.0);
    assertThat(done.timings().firstSentenceMs()).isNotNull();
  }

  @Test
  @DisplayName(
      "The pack's prompt comes first, the caller's context after it, and the guards judge only what was said")
  void thePackIsTheLaw() {
    answer("D'accord, je t'écoute.");
    ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);

    service.run(service.prepare(text("Je suis fatiguée."), user), events::add);

    verify(ai).stream(sent.capture());
    ChatRequest request = sent.getValue();
    assertThat(request.prompt()).isEqualTo("Je suis fatiguée.");
    assertThat(request.messages())
        .extracting(ChatRequest.ChatMessage::content)
        .containsExactly("Tu es une présence IA.", "Le fil : jeudi soir.");
    Map<String, Object> declared = request.context().preferences();
    assertThat(declared)
        .containsEntry(JobCommand.GUARD_SUBJECT_KEY, "Je suis fatiguée.")
        .containsEntry(JobCommand.DEFERRED_METERING_KEY, Boolean.TRUE)
        .containsEntry(JobCommand.SCOPE_REFUSED_TERMS_KEY, "posologie")
        .containsEntry(JobCommand.SAFETY_CRISIS_TERMS_KEY, "en finir")
        .containsEntry("preference.language", "fr")
        .containsKey(VoiceTurnUsageRegistry.TURN_KEY);
    assertThat(String.valueOf(declared.get(JobCommand.SAFETY_RESPONSE_KEY)))
        .startsWith("Je m'arrête là.")
        .contains("1 866 APPELLE");
  }

  @Test
  @DisplayName("An audio turn is heard first, and its minutes are billed with the answer, once")
  void audioTurnSettlesOnce() {
    when(transcriber.process(any(), eq(VoiceTurnService.TRANSCRIPTION_MODEL)))
        .thenReturn(new ProcessedAudioPayload(" Je suis fatiguée. ", new BigDecimal("6.0")));
    answer("Je t'entends, c'est beaucoup.");

    service.run(
        service.prepare(
            new VoiceTurn("conv-1", "fr-CA", new byte[] {1, 2}, null, null, INSTALLATION, null),
            user),
        events::add);

    assertThat(names().getFirst()).isEqualTo("transcript");
    assertThat(((VoiceTurnEvent.Transcript) events.getFirst()).text())
        .isEqualTo("Je suis fatiguée.");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MeteredStep>> steps = ArgumentCaptor.forClass(List.class);
    verify(credits).settleAggregate(eq("hold-1"), steps.capture(), eq("voice:hold-1"));
    assertThat(steps.getValue())
        .extracting(MeteredStep::capability, MeteredStep::modelName)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(BillableCapability.AUDIO, "whisper-base"),
            org.assertj.core.groups.Tuple.tuple(BillableCapability.AUDIO, "piper-fr-siwis-medium"));
    verify(credits, never()).release(anyString(), anyString());
  }

  @Test
  @DisplayName(
      "The metered legs: minutes, tokens and characters, each against its own row; nothing unmeasured")
  void meteredSteps() {
    List<MeteredStep> steps =
        VoiceTurnService.meteredSteps(
            SIWIS, new BigDecimal("6"), TokenUsage.reported(100, 200, 300), 120L);

    assertThat(steps).hasSize(3);
    assertThat(steps.getFirst().consumption().audioSeconds()).isEqualByComparingTo("6");
    assertThat(steps.get(1).capability()).isEqualTo(BillableCapability.CHAT);
    assertThat(steps.get(1).consumption().tokens()).isEqualTo(300L);
    assertThat(steps.get(2).consumption().characters()).isEqualTo(120L);
    assertThat(VoiceTurnService.meteredSteps(SIWIS, null, TokenUsage.none(), 0L)).isEmpty();
  }

  @Test
  @DisplayName(
      "The crisis guard's reply is the pack's text, spoken, with the region's line — and not billed")
  void crisisIsAnsweredAndReleased() {
    when(ai.stream(any()))
        .thenReturn(
            Flux.error(
                new PipelineShortCircuitException(
                    "SafetyInterceptor", "crisis_content", WELLBEING.crisisReply(), null)));

    service.run(service.prepare(text("Je pense à en finir."), user), events::add);

    assertThat(names()).containsExactly("safety.crisis", "done");
    VoiceTurnEvent.SafetyCrisis crisis = (VoiceTurnEvent.SafetyCrisis) events.getFirst();
    assertThat(crisis.response()).isEqualTo("Je m'arrête là.");
    assertThat(crisis.resource()).isEqualTo(QC);
    assertThat(crisis.text()).isEqualTo(WELLBEING.crisisReply());
    assertThat(crisis.audio().url()).startsWith(VoiceClipStore.PATH);
    ArgumentCaptor<AudioRequest> spoken = ArgumentCaptor.forClass(AudioRequest.class);
    verify(ai).audio(spoken.capture());
    assertThat(spoken.getValue().prompt()).isEqualTo(WELLBEING.crisisReply());
    verify(credits).release(eq("hold-1"), anyString());
    verify(credits, never()).settleAggregate(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("The scope guard's refusal is spoken verbatim, and not billed")
  void scopeIsAnswered() {
    when(ai.stream(any()))
        .thenReturn(
            Flux.error(
                new PipelineShortCircuitException(
                    "ScopeGuardInterceptor",
                    "out_of_declared_scope",
                    "Je ne parle pas de posologie.",
                    null)));

    service.run(service.prepare(text("Quelle posologie ?"), user), events::add);

    assertThat(names()).containsExactly("scope.refused", "done");
    assertThat(((VoiceTurnEvent.ScopeRefused) events.getFirst()).refusal())
        .isEqualTo("Je ne parle pas de posologie.");
    verify(credits).release(eq("hold-1"), anyString());
  }

  @Test
  @DisplayName(
      "A plan or balance refusal of the chat leg stops the turn, says nothing aloud, releases")
  void otherRefusalStops() {
    when(ai.stream(any()))
        .thenReturn(
            Flux.error(
                new PipelineShortCircuitException(
                    "EntitlementInterceptor", "capability_not_in_plan", "no chat", null)));

    service.run(service.prepare(text("Bonjour."), user), events::add);

    assertThat(names()).containsExactly("error");
    verify(ai, never()).audio(any());
    verify(credits).release(eq("hold-1"), anyString());
  }

  @Test
  @DisplayName(
      "An engine failure after the stream opened is an error event, and the hold goes back")
  void failureReleases() {
    when(ai.stream(any())).thenReturn(Flux.error(new IllegalStateException("ollama down")));

    service.run(service.prepare(text("Bonjour."), user), events::add);

    assertThat(names()).containsExactly("error");
    assertThat(((VoiceTurnEvent.Failed) events.getFirst()).message()).doesNotContain("ollama");
    verify(credits).release(eq("hold-1"), anyString());
  }

  @Test
  @DisplayName("Silence is not answered; what was transcribed is still billed")
  void silence() {
    when(transcriber.process(any(), anyString()))
        .thenReturn(new ProcessedAudioPayload("  ", new BigDecimal("2")));

    service.run(
        service.prepare(
            new VoiceTurn("conv-1", "fr-CA", new byte[] {1}, null, null, null, null), user),
        events::add);

    assertThat(names()).containsExactly("transcript", "done");
    verify(ai, never()).stream(any());
    verify(credits).settleAggregate(eq("hold-1"), any(), anyString());
  }

  @Test
  @DisplayName(
      "Refusals before the stream: unknown installation 404, no speech in the plan 403, no voice 422")
  void refusalsBeforeTheStream() {
    when(declarations.forInstallation(any(), anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.prepare(text("x"), user))
        .isInstanceOfSatisfying(
            VoiceTurnRefusedException.class,
            refused -> assertThat(refused.code()).isEqualTo("installation-not-found"));

    when(entitlements.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR.toString(), "free", Map.of(), 0L, Instant.now().plusSeconds(60)));
    VoiceTurn outside = new VoiceTurn("c", "fr-CA", null, "x", null, null, null);
    assertThatThrownBy(() -> service.prepare(outside, user))
        .isInstanceOfSatisfying(
            VoiceTurnRefusedException.class,
            refused -> assertThat(refused.code()).isEqualTo("capability-not-in-plan"));

    when(entitlements.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR.toString(),
                "premium",
                Map.of("capability.audio", "true"),
                0L,
                Instant.now().plusSeconds(60)));
    when(voices.defaultVoice("nl-BE")).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.prepare(new VoiceTurn("c", "nl-BE", null, "x", null, null, null), user))
        .isInstanceOfSatisfying(
            VoiceTurnRefusedException.class,
            refused -> assertThat(refused.code()).isEqualTo("no-voice"));
    verify(credits, never()).hold(any());
  }

  @Test
  @DisplayName("A named voice is the voice; billing off takes no hold and settles nothing")
  void namedVoiceAndBillingOff() {
    when(voices.findVoice("piper-fr-tom-medium"))
        .thenReturn(Optional.of(new SpeechVoice("piper-fr-tom-medium", "fr-FR")));
    when(credits.hold(any())).thenReturn(CreditHoldResponse.notMetered());
    answer("Je suis là pour t'écouter.");

    PreparedTurn prepared =
        service.prepare(
            new VoiceTurn("c", "fr-CA", null, "Bonsoir.", "piper-fr-tom-medium", null, null), user);
    service.run(prepared, events::add);

    assertThat(prepared.holdId()).isNull();
    assertThat(prepared.declarations()).isNull();
    VoiceTurnEvent.Sentence sentence =
        events.stream()
            .filter(VoiceTurnEvent.Sentence.class::isInstance)
            .map(VoiceTurnEvent.Sentence.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(sentence.audio().voice()).isEqualTo("piper-fr-tom-medium");
    verify(credits, never()).settleAggregate(anyString(), any(), anyString());
  }
}
