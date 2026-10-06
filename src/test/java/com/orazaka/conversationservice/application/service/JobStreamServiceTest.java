package com.orazaka.conversationservice.application.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.persistence.domain.event.JobStatusChangedEvent;
import com.orazaka.persistence.domain.model.JobDto;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class JobStreamServiceTest {
  private static final java.time.Clock FIXED_CLOCK =
      java.time.Clock.fixed(
          java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

  @Mock private JobPersistenceProvider jobPersistenceProvider;

  private JobStreamService service;

  @BeforeEach
  void setUp() {
    service = new JobStreamService(jobPersistenceProvider);
  }

  @Test
  void register_successful_returnsEmitterAndAddsToEmitters() {
    SseEmitter emitter = service.register("user-123");
    assertNotNull(emitter);
    assertEquals(1, service.getActiveConnectionCount());
  }

  @Test
  void sendHeartbeats_workingEmitters_sendsComment() {
    service.register("user-1");
    assertEquals(1, service.getActiveConnectionCount());

    // Call sendHeartbeats which should send comment to the active emitter
    assertDoesNotThrow(() -> service.sendHeartbeats());
    assertEquals(1, service.getActiveConnectionCount());
  }

  @Test
  void sendHeartbeats_throwingEmitter_evictsEmitter() throws Exception {
    // SseEmitter send throws IOException
    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new IOException("Dead connection"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    // Register a client first to create the entry in the map
    service.register("user-1");

    // Since we need to put mockEmitter into the emitters list, but it's private,
    // let's reflectively inject or mock the behavior.
    // Wait, let's use java reflection to add our mockEmitter to the map for testing.
    try {
      java.lang.reflect.Field field = JobStreamService.class.getDeclaredField("emitters");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> map =
          (Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);

      java.util.concurrent.CopyOnWriteArrayList<SseEmitter> list =
          map.computeIfAbsent("user-1", k -> new java.util.concurrent.CopyOnWriteArrayList<>());
      list.add(mockEmitter);
    } catch (Exception e) {
      fail("Reflection failed: " + e.getMessage());
    }

    // Now call sendHeartbeats. It should try to send to mockEmitter, catch IOException, and evict
    // it.
    service.sendHeartbeats();

    // The emitters list for user-1 should no longer contain mockEmitter (it might still contain
    // realEmitter)
    // Let's check connection count or verify it was evicted.
    // The connection count should only be 1 (realEmitter), meaning mockEmitter was successfully
    // evicted.
    assertEquals(1, service.getActiveConnectionCount());
  }

  @Test
  void handleJobStatusChanged_noEmitters_doesNothing() {
    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "RUNNING",
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    JobStatusChangedEvent event = new JobStatusChangedEvent(job);

    assertDoesNotThrow(() -> service.handleJobStatusChanged(event));
  }

  @Test
  void handleJobStatusChanged_withEmitters_broadcastsState() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);

    // Register user emitter via reflection to use mock
    try {
      java.lang.reflect.Field field = JobStreamService.class.getDeclaredField("emitters");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> map =
          (Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);
      java.util.concurrent.CopyOnWriteArrayList<SseEmitter> list =
          map.computeIfAbsent("user-1", k -> new java.util.concurrent.CopyOnWriteArrayList<>());
      list.add(mockEmitter);
    } catch (Exception e) {
      fail("Reflection failed: " + e.getMessage());
    }

    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "RUNNING",
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    JobStatusChangedEvent event = new JobStatusChangedEvent(job);

    service.handleJobStatusChanged(event);

    verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void broadcastProgress_withEmitters_broadcastsProgress() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    try {
      java.lang.reflect.Field field = JobStreamService.class.getDeclaredField("emitters");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> map =
          (Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);
      java.util.concurrent.CopyOnWriteArrayList<SseEmitter> list =
          map.computeIfAbsent("user-1", k -> new java.util.concurrent.CopyOnWriteArrayList<>());
      list.add(mockEmitter);
    } catch (Exception e) {
      fail("Reflection failed: " + e.getMessage());
    }

    service.broadcastProgress("job-1", "user-1", 50);

    verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void broadcastFailure_withJobFoundAndEmitters_broadcastsFailure() throws Exception {
    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "FAILED",
            Map.of(),
            Map.of(),
            "Error description",
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    when(jobPersistenceProvider.getJob("job-1")).thenReturn(Optional.of(job));

    SseEmitter mockEmitter = mock(SseEmitter.class);
    try {
      java.lang.reflect.Field field = JobStreamService.class.getDeclaredField("emitters");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> map =
          (Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);
      java.util.concurrent.CopyOnWriteArrayList<SseEmitter> list =
          map.computeIfAbsent("user-1", k -> new java.util.concurrent.CopyOnWriteArrayList<>());
      list.add(mockEmitter);
    } catch (Exception e) {
      fail("Reflection failed: " + e.getMessage());
    }

    service.broadcastFailure("job-1");

    verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void purgeAllEmitters_activeEmitters_completesAndClearsMap() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    try {
      java.lang.reflect.Field field = JobStreamService.class.getDeclaredField("emitters");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> map =
          (Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);
      java.util.concurrent.CopyOnWriteArrayList<SseEmitter> list =
          map.computeIfAbsent("user-1", k -> new java.util.concurrent.CopyOnWriteArrayList<>());
      list.add(mockEmitter);
    } catch (Exception e) {
      fail("Reflection failed: " + e.getMessage());
    }

    assertEquals(1, service.getActiveConnectionCount());
    service.purgeAllEmitters();
    assertEquals(0, service.getActiveConnectionCount());
    verify(mockEmitter).complete();
  }

  @Test
  void sendHeartbeats_throwingRuntimeException_evictsEmitter() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new RuntimeException("Generic error"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    service.sendHeartbeats();
    assertEquals(0, service.getActiveConnectionCount());
  }

  @Test
  void handleJobStatusChanged_throwingIOException_evictsEmitter() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new IOException("Dead connection"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "RUNNING",
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    JobStatusChangedEvent event = new JobStatusChangedEvent(job);

    service.handleJobStatusChanged(event);
    assertEquals(0, service.getActiveConnectionCount());
  }

  @Test
  void handleJobStatusChanged_throwingRuntimeException_evictsEmitter() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new RuntimeException("Generic error"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "RUNNING",
            Map.of(),
            Map.of(),
            null,
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    JobStatusChangedEvent event = new JobStatusChangedEvent(job);

    service.handleJobStatusChanged(event);
    assertEquals(0, service.getActiveConnectionCount());
  }

  @Test
  void broadcastProgress_throwingIOException_evictsEmitter() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new IOException("Dead connection"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    service.broadcastProgress("job-1", "user-1", 50);
    assertEquals(0, service.getActiveConnectionCount());
  }

  @Test
  void broadcastProgress_throwingRuntimeException_evictsEmitter() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new RuntimeException("Generic error"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    service.broadcastProgress("job-1", "user-1", 50);
    assertEquals(0, service.getActiveConnectionCount());
  }

  @Test
  void broadcastFailure_throwingIOException_evictsEmitter() throws Exception {
    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "FAILED",
            Map.of(),
            Map.of(),
            "Error description",
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    when(jobPersistenceProvider.getJob("job-1")).thenReturn(Optional.of(job));

    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new IOException("Dead connection"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    service.broadcastFailure("job-1");
    assertEquals(0, service.getActiveConnectionCount());
  }

  @Test
  void broadcastFailure_throwingRuntimeException_evictsEmitter() throws Exception {
    JobDto job =
        new JobDto(
            "job-1",
            "user-1",
            "feature-x",
            "FAILED",
            Map.of(),
            Map.of(),
            "Error description",
            Instant.now(FIXED_CLOCK),
            Instant.now(FIXED_CLOCK));
    when(jobPersistenceProvider.getJob("job-1")).thenReturn(Optional.of(job));

    SseEmitter mockEmitter = mock(SseEmitter.class);
    doThrow(new RuntimeException("Generic error"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    injectMockEmitter("user-1", mockEmitter);

    service.broadcastFailure("job-1");
    assertEquals(0, service.getActiveConnectionCount());
  }

  private void injectMockEmitter(String userId, SseEmitter mockEmitter) {
    try {
      java.lang.reflect.Field field = JobStreamService.class.getDeclaredField("emitters");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> map =
          (Map<String, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);
      java.util.concurrent.CopyOnWriteArrayList<SseEmitter> list =
          map.computeIfAbsent(userId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
      list.add(mockEmitter);
    } catch (Exception e) {
      fail("Reflection failed: " + e.getMessage());
    }
  }
}
