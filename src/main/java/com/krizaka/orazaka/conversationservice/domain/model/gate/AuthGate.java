package com.krizaka.orazaka.conversationservice.domain.model.gate;

import java.util.List;

/**
 * Authentication gate — validates identity claims before intent routing.
 *
 * @param userId Authenticated principal identifier.
 * @param scopes Granted JWT scopes for the current session.
 * @param verified Whether multi-factor verification has passed.
 */
public record AuthGate(String userId, List<String> scopes, boolean verified) implements StaticGate {

  public AuthGate {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("AuthGate requires a non-blank userId");
    }
    if (scopes == null) {
      throw new IllegalArgumentException("AuthGate requires non-null scopes");
    }
    scopes = List.copyOf(scopes);
  }
}
