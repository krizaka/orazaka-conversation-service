package com.orazaka.conversationservice.infrastructure.adapter.persistence;

import com.krizaka.users.domain.port.UserDirectoryClient;
import com.orazaka.core.domain.ports.outbound.UserCredentialsProvider;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Implementation of UserCredentialsProvider port to resolve decrypted API key credentials for core.
 */
@Service
class UserCredentialsProviderAdapter implements UserCredentialsProvider {

  private final UserDirectoryClient userDirectoryService;

  UserCredentialsProviderAdapter(UserDirectoryClient userDirectoryService) {
    this.userDirectoryService =
        Objects.requireNonNull(userDirectoryService, "UserDirectoryClient must not be null");
  }

  @Override
  public Optional<String> getDecryptedApiKey(String userId, String providerName) {
    if (userId == null || providerName == null) {
      return Optional.empty();
    }
    return userDirectoryService.getDecryptedApiKey(userId, providerName);
  }
}
