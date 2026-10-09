package com.krizaka.orazaka.conversationservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.port.CreditAuthorizationClient;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class JobMeteringServiceTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  @Mock private CreditAuthorizationClient creditAuthorizationClient;

  @Mock private com.krizaka.billing.domain.port.UnmeteredTurnRepository unmeteredTurns;

  /**
   * The billable capability is now read from the capability row rather than derived from a
   * contains() chain over the feature key (ADR-038), so the row is part of the fixture.
   */
  @Mock
  private com.krizaka.orazaka.persistence.domain.ports.inbound.CapabilityManager capabilityManager;

  /** Seeds the capability rows these tests submit against, as the registry would hold them. */
  @org.junit.jupiter.api.BeforeEach
  void seedCapabilityRows() {
    billsAs("orazaka.core.media.video", "VIDEO");
    billsAs("orazaka.core.media.image", "IMAGE");
    billsAs("orazaka.core.media.vision", "IMAGE");
    billsAs("orazaka.core.media.speech", "AUDIO");
    billsAs("orazaka.core.media.audio.analysis", "AUDIO");
    billsAs("orazaka.core.media.video.analysis", "VIDEO");
  }

  private void billsAs(String featureKey, String billableCapability) {
    lenient()
        .when(capabilityManager.findByFeatureKey(featureKey))
        .thenReturn(
            java.util.Optional.of(
                new com.krizaka.orazaka.jobs.domain.model.CapabilityDeclaration(
                    featureKey,
                    "h",
                    "job.media.generate",
                    null,
                    billableCapability,
                    "BATCH",
                    "{}",
                    "{}",
                    true)));
  }

  private JobMeteringService service() {
    return new JobMeteringService(creditAuthorizationClient, capabilityManager, unmeteredTurns);
  }

  private static CreditHoldResponse granted(String holdId) {
    return new CreditHoldResponse(holdId, true, false, 360, 4640, 1);
  }

  @Test
  void constructor_nullClient_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new JobMeteringService(null, capabilityManager, unmeteredTurns));
  }

  @Test
  void stampsTheHoldOntoThePayload_soTheExecutorCanReportItBack() {
    when(creditAuthorizationClient.hold(any())).thenReturn(granted("hold-7"));
    var message =
        new JobCommand("job-1", ACTOR, "orazaka.core.media.video", "wan-2.1", Map.of("p", "x"));

    JobCommand authorized = service().authorize(message);

    assertEquals("hold-7", authorized.holdId());
    assertEquals("job-1", authorized.correlationId());
    assertEquals("x", authorized.payload().get("p"));
  }

  @Test
  void pricesAgainstTheCapabilityAndModelTheRequestResolved() {
    when(creditAuthorizationClient.hold(any())).thenReturn(granted("hold-8"));
    var message = new JobCommand("job-2", ACTOR, "orazaka.core.media.video", "wan-2.1", Map.of());

    service().authorize(message);

    ArgumentCaptor<CreditHoldCommand> captor = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(creditAuthorizationClient).hold(captor.capture());
    CreditHoldCommand command = captor.getValue();
    assertEquals(ACTOR, command.actorId());
    assertEquals(BillableCapability.VIDEO, command.capability());
    assertEquals("wan-2.1", command.modelName());
    assertEquals("job-2", command.jobId());
    // Zero: the pricebook prices the hold. A producer that priced itself would be a second copy.
    assertEquals(0L, command.estimatedCredits());
  }

  @Test
  void unresolvedModel_asksThePricebookForTheCapabilityDefault() {
    when(creditAuthorizationClient.hold(any())).thenReturn(granted("hold-9"));

    service().authorize(new JobCommand("job-3", ACTOR, "orazaka.core.media.image", Map.of()));

    ArgumentCaptor<CreditHoldCommand> captor = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(creditAuthorizationClient).hold(captor.capture());
    assertNull(captor.getValue().modelName());
    assertEquals(BillableCapability.IMAGE, captor.getValue().capability());
  }

  @Test
  void speechAndTranscriptionBothBillAsAudio() {
    when(creditAuthorizationClient.hold(any())).thenReturn(granted("hold-10"));

    service().authorize(new JobCommand("job-4", ACTOR, "orazaka.core.media.speech", Map.of()));
    service()
        .authorize(new JobCommand("job-5", ACTOR, "orazaka.core.media.audio.analysis", Map.of()));

    ArgumentCaptor<CreditHoldCommand> captor = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(creditAuthorizationClient, org.mockito.Mockito.times(2)).hold(captor.capture());
    captor.getAllValues().forEach(c -> assertEquals(BillableCapability.AUDIO, c.capability()));
  }

  @Test
  void visionAnalysisBillsAsImage() {
    when(creditAuthorizationClient.hold(any())).thenReturn(granted("hold-11"));

    service().authorize(new JobCommand("job-6", ACTOR, "orazaka.core.media.vision", Map.of()));

    ArgumentCaptor<CreditHoldCommand> captor = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(creditAuthorizationClient).hold(captor.capture());
    assertEquals(BillableCapability.IMAGE, captor.getValue().capability());
  }

  @Test
  void videoAnalysisBillsAsVideo_notAsTheChatFallback() {
    when(creditAuthorizationClient.hold(any())).thenReturn(granted("hold-12"));

    service()
        .authorize(new JobCommand("job-7", ACTOR, "orazaka.core.media.video.analysis", Map.of()));

    ArgumentCaptor<CreditHoldCommand> captor = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(creditAuthorizationClient).hold(captor.capture());
    assertEquals(BillableCapability.VIDEO, captor.getValue().capability());
  }

  @Test
  void unmeteredGrant_leavesTheMessageUnstamped() {
    // The no-op adapter grants without reserving; propagating its id would create a phantom hold
    // that the settlement consumer would then try to close.
    when(creditAuthorizationClient.hold(any())).thenReturn(CreditHoldResponse.notMetered());
    var message = new JobCommand("job-8", ACTOR, "orazaka.core.media.image", Map.of());

    JobCommand authorized = service().authorize(message);

    assertSame(message, authorized);
    assertNull(authorized.holdId());
  }

  @Test
  void refusalPropagates_soTheSubmissionAnswers402() {
    when(creditAuthorizationClient.hold(any()))
        .thenThrow(new InsufficientCreditsException(ACTOR, BillableCapability.VIDEO, 360, 12));
    var message = new JobCommand("job-9", ACTOR, "orazaka.core.media.video", Map.of());

    assertThrows(InsufficientCreditsException.class, () -> service().authorize(message));
  }

  @Test
  void billingOutage_failsOpen_ratherThanTakingSubmissionDownWithIt() {
    when(creditAuthorizationClient.hold(any()))
        .thenThrow(new RestClientException("connect timed out"));
    var message = new JobCommand("job-10", ACTOR, "orazaka.core.media.image", Map.of());

    JobCommand authorized = service().authorize(message);

    assertSame(message, authorized);
    assertNull(authorized.holdId());
    // ADR-064: open, and accounted — a durable record of the unmetered job, not a log line.
    var recorded =
        org.mockito.ArgumentCaptor.forClass(com.krizaka.billing.domain.model.UnmeteredTurn.class);
    verify(unmeteredTurns).record(recorded.capture());
    assertEquals("job-10", recorded.getValue().correlationId());
    assertEquals(ACTOR, recorded.getValue().actorId());
    assertEquals(
        com.krizaka.billing.domain.model.BillableCapability.IMAGE,
        recorded.getValue().capability());
  }

  @Test
  void billingOutage_withNoWayToRecordIt_isNotSubmitted() {
    when(creditAuthorizationClient.hold(any()))
        .thenThrow(new RestClientException("connect timed out"));
    doThrow(new IllegalStateException("outbox unavailable")).when(unmeteredTurns).record(any());
    var message = new JobCommand("job-12", ACTOR, "orazaka.core.media.image", Map.of());

    assertThrows(IllegalStateException.class, () -> service().authorize(message));
  }

  @Test
  void jobWithNoActor_isNotMetered_becauseThereIsNoWalletToCharge() {
    var message = new JobCommand("job-11", null, "orazaka.core.media.image", Map.of());

    JobCommand authorized = service().authorize(message);

    assertSame(message, authorized);
    verifyNoInteractions(creditAuthorizationClient);
  }

  @Test
  void nullMessage_throws() {
    assertThrows(NullPointerException.class, () -> service().authorize(null));
  }
}
