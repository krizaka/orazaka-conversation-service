package com.krizaka.orazaka.conversationservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.krizaka.orazaka.conversationservice.application.service.JobStreamService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletEventListenerTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  @Mock private JobStreamService jobStreamService;

  private WalletEventListener listener() {
    return new WalletEventListener(jobStreamService);
  }

  private static Map<String, Object> creditGranted() {
    Map<String, Object> event = new HashMap<>();
    event.put("actorId", ACTOR);
    event.put("amount", 500);
    event.put("balanceAfter", 1500);
    event.put("reason", "goodwill");
    event.put("createdBy", "admin-1");
    return event;
  }

  private static Map<String, Object> lowBalance() {
    Map<String, Object> event = new HashMap<>();
    event.put("actorId", ACTOR);
    event.put("available", 120);
    event.put("thresholdPercent", 10);
    return event;
  }

  @Test
  void constructor_rejectsNullStream() {
    assertThrows(NullPointerException.class, () -> new WalletEventListener(null));
  }

  @Test
  @DisplayName("a credit grant reaches the user's stream as a balance update")
  void pushesABalanceUpdate() {
    listener().onWalletEvent(creditGranted());

    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(jobStreamService)
        .broadcastWalletEvent(eq(ACTOR), eq("wallet-balance"), payload.capture());
    assertEquals(1500, payload.getValue().get("balanceAfter"));
    assertEquals("goodwill", payload.getValue().get("reason"));
  }

  @Test
  @DisplayName("a low-balance warning arrives under its own name — banner, not toast")
  void pushesALowBalanceWarningSeparately() {
    listener().onWalletEvent(lowBalance());

    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(jobStreamService).broadcastWalletEvent(eq(ACTOR), eq("wallet-low"), payload.capture());
    assertEquals(120, payload.getValue().get("available"));
    assertEquals(10, payload.getValue().get("thresholdPercent"));
  }

  @Test
  @DisplayName("the admin who made an adjustment is not pushed to the user's browser")
  void doesNotLeakTheAdminIdentity() {
    listener().onWalletEvent(creditGranted());

    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(jobStreamService).broadcastWalletEvent(anyString(), anyString(), payload.capture());
    assertFalse(
        payload.getValue().containsKey("createdBy"),
        "who granted the credit is an internal audit fact, not something the user is shown");
  }

  @Test
  @DisplayName("an event with no actor is dropped rather than broadcast to nobody")
  void ignoresAnEventWithNoActor() {
    listener().onWalletEvent(Map.of("amount", 100));

    verifyNoInteractions(jobStreamService);
  }

  @Test
  @DisplayName("a malformed actor is dropped rather than taking the listener down")
  void ignoresABlankActor() {
    listener().onWalletEvent(Map.of("actorId", "   "));

    verifyNoInteractions(jobStreamService);
  }
}
