package com.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.conversationservice.application.service.JobQueuePublisherService;
import com.orazaka.conversationservice.application.service.JobStreamService;
import com.orazaka.core.domain.model.job.JobInfo;
import com.orazaka.core.domain.model.job.JobStatus;
import com.orazaka.core.domain.ports.inbound.ChatSessionService;
import com.orazaka.core.domain.ports.inbound.JobService;
import com.orazaka.identity.domain.exception.InvalidRequestException;
import com.orazaka.identity.domain.model.User;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class JobControllerTest {
  private static final java.time.Clock FIXED_CLOCK =
      java.time.Clock.fixed(
          java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

  private JobService jobService;
  private JobQueuePublisherService jobQueuePublisher;
  private JobStreamService jobStreamService;
  private ChatSessionService chatSessionService;
  private ObjectProvider<StatefulRedisConnection<String, byte[]>> redisConnectionProvider;
  private StatefulRedisConnection<String, byte[]> redisConnection;
  private RedisCommands<String, byte[]> redisCommands;
  private JobController jobController;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jobService = mock(JobService.class);
    jobQueuePublisher = mock(JobQueuePublisherService.class);
    jobStreamService = mock(JobStreamService.class);
    chatSessionService = mock(ChatSessionService.class);
    redisConnectionProvider = mock(ObjectProvider.class);
    redisConnection = mock(StatefulRedisConnection.class);
    redisCommands = mock(RedisCommands.class);
    jobController =
        new JobController(
            jobService,
            jobQueuePublisher,
            jobStreamService,
            chatSessionService,
            redisConnectionProvider);
  }

  @Test
  void testGetJobsAdmin() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "admin", "admin@test.class", true, Set.of("ROLE_ADMIN"), Map.of());
    Page<JobInfo> expectedPage =
        new PageImpl<>(
            List.of(
                new JobInfo(
                    "job-1",
                    userId.toString(),
                    "video",
                    JobStatus.PROCESSING,
                    Map.of(),
                    Map.of(),
                    null,
                    Instant.now(FIXED_CLOCK),
                    Instant.now(FIXED_CLOCK))));
    when(jobService.getAllJobs(any(PageRequest.class))).thenReturn(expectedPage);

    ResponseEntity<Page<JobInfo>> response = jobController.getJobs(0, 10, user);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedPage, response.getBody());
    verify(jobService).getAllJobs(any(PageRequest.class));
    verify(jobService, never()).getJobsByUserId(anyString(), any(PageRequest.class));
  }

  @Test
  void testGetJobsUser() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "user", "user@test.class", true, Set.of("ROLE_USER"), Map.of());
    Page<JobInfo> expectedPage =
        new PageImpl<>(
            List.of(
                new JobInfo(
                    "job-1",
                    userId.toString(),
                    "video",
                    JobStatus.PROCESSING,
                    Map.of(),
                    Map.of(),
                    null,
                    Instant.now(FIXED_CLOCK),
                    Instant.now(FIXED_CLOCK))));
    when(jobService.getJobsByUserId(eq(userId.toString()), any(PageRequest.class)))
        .thenReturn(expectedPage);

    ResponseEntity<Page<JobInfo>> response = jobController.getJobs(0, 10, user);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedPage, response.getBody());
    verify(jobService, never()).getAllJobs(any(PageRequest.class));
    verify(jobService).getJobsByUserId(eq(userId.toString()), any(PageRequest.class));
  }

  @Test
  void testGetJobFound() {
    JobInfo info =
        new JobInfo(
            "job-1",
            "user-1",
            "video",
            JobStatus.COMPLETED,
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    when(jobService.getJob("job-1")).thenReturn(Optional.of(info));

    ResponseEntity<JobInfo> response = jobController.getJob("job-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(info, response.getBody());
  }

  @Test
  void testGetJobNotFound() {
    when(jobService.getJob("job-1")).thenReturn(Optional.empty());

    ResponseEntity<JobInfo> response = jobController.getJob("job-1");

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void testStreamJobs() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "user", "user@test.class", true, Set.of("ROLE_USER"), Map.of());
    SseEmitter emitter = new SseEmitter();
    when(jobStreamService.register(userId.toString())).thenReturn(emitter);

    SseEmitter result = jobController.streamJobs(user);

    assertEquals(emitter, result);
  }

  @Test
  void testJobProgressRequestValidation() {
    // Valid
    var req = new JobController.JobProgressRequest(50);
    assertEquals(50, req.progress());

    // Null
    assertThrows(NullPointerException.class, () -> new JobController.JobProgressRequest(null));

    // Negative
    assertThrows(InvalidRequestException.class, () -> new JobController.JobProgressRequest(-5));

    // Too high
    assertThrows(InvalidRequestException.class, () -> new JobController.JobProgressRequest(105));
  }

  @Test
  void testUpdateJobProgressFound() {
    JobInfo info =
        new JobInfo(
            "job-1",
            "user-1",
            "video",
            JobStatus.PROCESSING,
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    when(jobService.getJob("job-1")).thenReturn(Optional.of(info));

    ResponseEntity<Void> response =
        jobController.updateJobProgress("job-1", new JobController.JobProgressRequest(40));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(jobStreamService).broadcastProgress("job-1", "user-1", 40);
  }

  @Test
  void testUpdateJobProgressNotFound() {
    when(jobService.getJob("job-1")).thenReturn(Optional.empty());

    ResponseEntity<Void> response =
        jobController.updateJobProgress("job-1", new JobController.JobProgressRequest(40));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(jobStreamService, never()).broadcastProgress(anyString(), anyString(), anyInt());
  }

  // ── Admin maintenance (merged from AdminJobController) ────────────────────

  @Test
  void purgeTestData_successfulExecution_cleansEverything() {
    doAnswer(
            invocation -> {
              Consumer<StatefulRedisConnection<String, byte[]>> consumer =
                  invocation.getArgument(0);
              consumer.accept(redisConnection);
              return null;
            })
        .when(redisConnectionProvider)
        .ifAvailable(any());
    when(redisConnection.sync()).thenReturn(redisCommands);
    when(redisCommands.keys(anyString())).thenReturn(List.of("key-1"));
    when(redisCommands.del(any(String[].class))).thenReturn(1L);

    ResponseEntity<Void> response = jobController.purgeTestData();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<String> testUserIds =
        List.of("550e8400-e29b-41d4-a716-446655440001", "550e8400-e29b-41d4-a716-446655440002");
    for (String userId : testUserIds) {
      verify(jobService).purgeJobsByUserId(userId);
      verify(chatSessionService).purgeSessionsByUserId(userId);
    }
    verify(redisCommands).flushdb();
    verify(redisCommands, times(2)).keys(anyString());
    verify(redisCommands, times(2)).del(any(String[].class));
    verify(jobStreamService).purgeAllEmitters();
  }

  @Test
  void purgeTestData_databaseException_continuesGracefully() {
    doThrow(new TransientDataAccessResourceException("DB Error"))
        .when(jobService)
        .purgeJobsByUserId(anyString());

    ResponseEntity<Void> response = jobController.purgeTestData();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(jobStreamService).purgeAllEmitters();
  }

  @Test
  void purgeTestData_redisException_continuesGracefully() {
    doAnswer(
            invocation -> {
              Consumer<StatefulRedisConnection<String, byte[]>> consumer =
                  invocation.getArgument(0);
              consumer.accept(redisConnection);
              return null;
            })
        .when(redisConnectionProvider)
        .ifAvailable(any());
    when(redisConnection.sync()).thenThrow(new RuntimeException("Redis connection error"));

    ResponseEntity<Void> response = jobController.purgeTestData();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(jobStreamService).purgeAllEmitters();
  }

  @Test
  void purgeTestData_sseException_continuesGracefully() {
    doThrow(new RuntimeException("SSE purge error")).when(jobStreamService).purgeAllEmitters();

    ResponseEntity<Void> response = jobController.purgeTestData();

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void getActiveConnections_returnsCount() {
    when(jobStreamService.getActiveConnectionCount()).thenReturn(42);

    ResponseEntity<Integer> response = jobController.getActiveConnections();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(42, response.getBody());
  }
}
