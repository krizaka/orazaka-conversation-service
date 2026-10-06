package com.orazaka.conversationservice.infrastructure.adapter.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.conversationservice.application.service.UserDirectoryService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserCredentialsProviderAdapterTest {

  private final UserDirectoryService userDirectoryService = mock(UserDirectoryService.class);
  private final UserCredentialsProviderAdapter provider =
      new UserCredentialsProviderAdapter(userDirectoryService);

  @Test
  void getDecryptedApiKey_delegatesToUserDirectoryService() {
    when(userDirectoryService.getDecryptedApiKey("user-1", "openai"))
        .thenReturn(Optional.of("sk-test-key"));
    Optional<String> result = provider.getDecryptedApiKey("user-1", "openai");
    assertTrue(result.isPresent());
    assertEquals("sk-test-key", result.get());
  }

  @Test
  void getDecryptedApiKey_returnsEmpty_whenNotFound() {
    when(userDirectoryService.getDecryptedApiKey("user-1", "unknown")).thenReturn(Optional.empty());
    Optional<String> result = provider.getDecryptedApiKey("user-1", "unknown");
    assertTrue(result.isEmpty());
  }

  @Test
  void getDecryptedApiKey_nullUserId_returnsEmpty() {
    Optional<String> result = provider.getDecryptedApiKey(null, "openai");
    assertTrue(result.isEmpty());
    verifyNoInteractions(userDirectoryService);
  }

  @Test
  void getDecryptedApiKey_nullProviderName_returnsEmpty() {
    Optional<String> result = provider.getDecryptedApiKey("user-1", null);
    assertTrue(result.isEmpty());
    verifyNoInteractions(userDirectoryService);
  }

  @Test
  void constructor_nullUserDirectoryService_throws() {
    assertThrows(NullPointerException.class, () -> new UserCredentialsProviderAdapter(null));
  }
}
