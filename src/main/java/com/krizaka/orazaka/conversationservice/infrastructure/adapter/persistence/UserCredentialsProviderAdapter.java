package com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.core.domain.ports.outbound.UserCredentialsProvider;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves a user's own provider key for one chat turn, on the user's authority and nothing else.
 *
 * <p>The identity service releases a key only against a grant issued on the user's own session
 * (GHSA-3pf7-rgf2-4x3m): this adapter takes the session bearer of the request being served from the
 * security context, asks for a grant bound to this turn, and redeems it at once. A call without a
 * user session on the thread (a background thread, another user's id) gets no key, so the engine
 * falls back to the platform model.
 */
@Service
class UserCredentialsProviderAdapter implements UserCredentialsProvider {

  private static final Logger logger = LoggerFactory.getLogger(UserCredentialsProviderAdapter.class);

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
    Optional<String> session = sessionOf(userId);
    if (session.isEmpty()) {
      logger.warn("No session of user {} on this thread, the provider key is not released.", userId);
      return Optional.empty();
    }
    String turnId = "turn-" + UUID.randomUUID();
    return userDirectoryService
        .grantCredential(session.get(), providerName, turnId)
        .flatMap(grant -> userDirectoryService.useCredential(grant, providerName, turnId));
  }

  /** The bearer of the authenticated user, only when it is the user the key is asked for. */
  private static Optional<String> sessionOf(String userId) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof User user
        && user.id().toString().equals(userId)
        && authentication.getCredentials() instanceof String token
        && !token.isBlank()) {
      return Optional.of(token);
    }
    return Optional.empty();
  }
}
