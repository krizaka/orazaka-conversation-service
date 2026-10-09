package com.orazaka.conversationservice.application.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.krizaka.users.domain.port.UserDirectoryClient;
import com.orazaka.billing.domain.exception.InsufficientCreditsException;
import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.persistence.domain.model.OutboxMessage;
import com.orazaka.persistence.domain.ports.inbound.OutboxStore;
import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

@ExtendWith(MockitoExtension.class)
class JobQueuePublisherServiceTest {

  @Mock private OutboxStore outboxStore;
  @Mock private JdbcClient jdbcClient;
  @Mock private UserDirectoryClient userDirectoryService;
  @Mock private JobMeteringService jobMeteringService;

  /**
   * Routing is read from the capability row now, not derived from the feature key (ADR-038), so the
   * route is part of the fixture rather than an implicit consequence of the key's spelling.
   */
  @Mock private com.orazaka.jobs.domain.port.CapabilityRoutingClient capabilityRoutingClient;

  /** Seeds the routes these tests publish against, as the registry would hold them. */
  @BeforeEach
  void seedRoutes() {
    routes("feature", MessagingContract.JOB_TEXT_PROCESS);
    routes("IMAGE_GEN", MessagingContract.JOB_MEDIA_GENERATE);
    routes("orazaka.core.media.video", MessagingContract.JOB_VIDEO_GENERATE);
  }

  private void routes(String featureKey, String routingKey) {
    lenient()
        .when(capabilityRoutingClient.route(featureKey))
        .thenReturn(
            java.util.Optional.of(
                new com.orazaka.jobs.domain.model.CapabilityRoute(
                    featureKey, routingKey, null, "CHAT", "BATCH", true)));
  }

  private JobQueuePublisherService service;

  @BeforeEach
  void setUp() {
    service =
        new JobQueuePublisherService(
            outboxStore,
            jdbcClient,
            userDirectoryService,
            jobMeteringService,
            capabilityRoutingClient);
  }

  @Test
  void constructor_nullOutboxStore_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JobQueuePublisherService(
                null,
                jdbcClient,
                userDirectoryService,
                jobMeteringService,
                capabilityRoutingClient));
  }

  @Test
  void constructor_nullJdbcClient_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JobQueuePublisherService(
                outboxStore,
                null,
                userDirectoryService,
                jobMeteringService,
                capabilityRoutingClient));
  }

  @Test
  void constructor_nullUserDirectory_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JobQueuePublisherService(
                outboxStore, jdbcClient, null, jobMeteringService, capabilityRoutingClient));
  }

  @Test
  void constructor_nullJobMetering_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JobQueuePublisherService(
                outboxStore, jdbcClient, userDirectoryService, null, capabilityRoutingClient));
  }

  @Test
  void publish_appendsJobMessageToOutbox() {
    var message = new JobCommand("job-1", "user-1", "feature", Map.of());
    when(jobMeteringService.authorize(message)).thenReturn(message);

    service.publish(message);

    verify(outboxStore)
        .append(
            new OutboxMessage(
                "job",
                "job-1",
                MessagingContract.JOBS_EXCHANGE,
                MessagingContract.JOB_TEXT_PROCESS,
                message));
  }

  @Test
  void publish_mediaFeature_routesToMediaKey() {
    var message = new JobCommand("job-9", "user-1", "IMAGE_GEN", Map.of());
    when(jobMeteringService.authorize(message)).thenReturn(message);

    service.publish(message);

    verify(outboxStore)
        .append(
            new OutboxMessage(
                "job",
                "job-9",
                MessagingContract.JOBS_EXCHANGE,
                MessagingContract.JOB_MEDIA_GENERATE,
                message));
  }

  @Test
  void publish_appendsTheAuthorizedMessage_soTheHoldTravelsWithTheJob() {
    var submitted = new JobCommand("job-3", "user-1", "feature", Map.of());
    var authorized = submitted.withReservation("hold-7", "job-3");
    when(jobMeteringService.authorize(submitted)).thenReturn(authorized);

    service.publish(submitted);

    verify(outboxStore)
        .append(
            new OutboxMessage(
                "job",
                "job-3",
                MessagingContract.JOBS_EXCHANGE,
                MessagingContract.JOB_TEXT_PROCESS,
                authorized.withDeferredMetering()));
  }

  /**
   * The hold taken here is the only one this job may cost [BILL-001].
   *
   * <p>Without the marker the downstream pipeline's {@code EntitlementInterceptor} took a second
   * CHAT hold, and one submitted chat job debited two credits for one inference (ADR-045).
   */
  @Test
  void publish_reserved_marksTheJobAsMeteredSoThePipelineTakesNoSecondHold() {
    var submitted = new JobCommand("job-5", "user-1", "feature", Map.of());
    when(jobMeteringService.authorize(submitted))
        .thenReturn(submitted.withReservation("hold-9", "job-5"));

    service.publish(submitted);

    var appended = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outboxStore).append(appended.capture());
    assertTrue(((JobCommand) appended.getValue().payload()).deferredMetering());
  }

  /**
   * Billing off or unreachable takes no hold, so nothing downstream must be told one exists.
   *
   * <p>Claiming to meter what nobody metered would leave the work free rather than double-charged —
   * the opposite error, and the reason the marker is conditional on the reservation.
   */
  @Test
  void publish_unmetered_claimsNoMeteringSoTheWorkIsNotSilentlyFree() {
    var submitted = new JobCommand("job-6", "user-1", "feature", Map.of());
    when(jobMeteringService.authorize(submitted)).thenReturn(submitted);

    service.publish(submitted);

    var appended = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outboxStore).append(appended.capture());
    assertFalse(((JobCommand) appended.getValue().payload()).deferredMetering());
  }

  @Test
  void publish_refused_neverReachesTheOutbox() {
    var message = new JobCommand("job-4", "user-1", "orazaka.core.media.video", Map.of());
    when(jobMeteringService.authorize(message))
        .thenThrow(new InsufficientCreditsException("user-1", BillableCapability.VIDEO, 360, 12));

    assertThrows(InsufficientCreditsException.class, () -> service.publish(message));

    verifyNoInteractions(outboxStore);
  }

  @Test
  void publishApproval_appendsApprovalToOutbox() {
    service.publishApproval("job-2", "user-2");

    verify(outboxStore)
        .append(
            new OutboxMessage(
                "job",
                "job-2",
                MessagingContract.JOBS_EXCHANGE,
                MessagingContract.JOB_AUTOMATION_APPROVED,
                Map.of("jobId", "job-2", "userId", "user-2", "action", "APPROVED")));
  }

  @Test
  void publishApproval_withNullJobId_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> service.publishApproval(null, "user-2"));
  }

  @Test
  void publishApproval_withSanitizationNeeded() {
    service.publishApproval("job\r\n-2", "user-2");

    verify(outboxStore)
        .append(
            new OutboxMessage(
                "job",
                "job\r\n-2",
                MessagingContract.JOBS_EXCHANGE,
                MessagingContract.JOB_AUTOMATION_APPROVED,
                Map.of("jobId", "job\r\n-2", "userId", "user-2", "action", "APPROVED")));
  }
}
