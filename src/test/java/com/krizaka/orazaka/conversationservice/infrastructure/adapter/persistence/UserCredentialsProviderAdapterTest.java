package com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.krizaka.users.domain.model.CredentialGrant;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The provider key is asked on the user's own session, per turn, and never without one. */
class UserCredentialsProviderAdapterTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final String SESSION = "session.jwt.value";

  private final UserDirectoryClient directory = mock(UserDirectoryClient.class);
  private final UserCredentialsProviderAdapter provider =
      new UserCredentialsProviderAdapter(directory);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static void signIn(UUID id, String token) {
    var user =
        new User(
            id, "eric", "eric@example.test", true, Set.of("USER"), Map.of(), List.of(), null);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, token, List.of()));
  }

  @Test
  void grantsOnTheUsersSessionThenRedeemsForTheSameTurn() {
    signIn(USER_ID, SESSION);
    var grant = new CredentialGrant("grant-value", Instant.EPOCH);
    when(directory.grantCredential(eq(SESSION), eq("openai"), anyString()))
        .thenReturn(Optional.of(grant));
    when(directory.useCredential(eq(grant), eq("openai"), anyString()))
        .thenReturn(Optional.of("sk-test-key"));

    Optional<String> key = provider.getDecryptedApiKey(USER_ID.toString(), "openai");

    assertEquals(Optional.of("sk-test-key"), key);
    var granted = ArgumentCaptor.forClass(String.class);
    var used = ArgumentCaptor.forClass(String.class);
    verify(directory).grantCredential(eq(SESSION), eq("openai"), granted.capture());
    verify(directory).useCredential(eq(grant), eq("openai"), used.capture());
    assertEquals(granted.getValue(), used.getValue());
    assertTrue(granted.getValue().startsWith("turn-"));
  }

  @Test
  void noKeyStored_isEmpty() {
    signIn(USER_ID, SESSION);
    when(directory.grantCredential(eq(SESSION), eq("openai"), anyString()))
        .thenReturn(Optional.empty());

    assertTrue(provider.getDecryptedApiKey(USER_ID.toString(), "openai").isEmpty());
    verify(directory, never()).useCredential(any(), anyString(), anyString());
  }

  @Test
  void noSessionOnTheThread_isEmptyAndAsksNothing() {
    assertTrue(provider.getDecryptedApiKey(USER_ID.toString(), "openai").isEmpty());
    verifyNoInteractions(directory);
  }

  @Test
  void anotherUsersKey_isNeverAsked() {
    signIn(UUID.randomUUID(), SESSION);

    assertTrue(provider.getDecryptedApiKey(USER_ID.toString(), "openai").isEmpty());
    verifyNoInteractions(directory);
  }

  @Test
  void nullArguments_areEmpty() {
    signIn(USER_ID, SESSION);

    assertTrue(provider.getDecryptedApiKey(null, "openai").isEmpty());
    assertTrue(provider.getDecryptedApiKey(USER_ID.toString(), null).isEmpty());
    verifyNoInteractions(directory);
  }

  @Test
  void constructor_nullUserDirectoryService_throws() {
    assertThrows(NullPointerException.class, () -> new UserCredentialsProviderAdapter(null));
  }
}
