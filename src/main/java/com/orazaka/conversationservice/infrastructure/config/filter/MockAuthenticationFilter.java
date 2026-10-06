package com.orazaka.conversationservice.infrastructure.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter that mocks authentication for development testing when the {@code dev} profile is active.
 *
 * <p>Intercepts requests with the {@code Bearer user-mock} authorization header, loads the mock dev
 * user profile via {@link AuthenticationManager}, and populates the {@link SecurityContextHolder}.
 */
@Component
@Profile("dev")
class MockAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger MOCK_AUTH_LOGGER =
      LoggerFactory.getLogger(MockAuthenticationFilter.class);

  /**
   * Development-only admin user UUID. Loaded from typed Spring property for traceability. This is
   * NOT a password — it is a static user identifier for local dev seeding.
   */
  private final String devAdminUserId;

  private final AuthenticationManager authenticationManager;

  public MockAuthenticationFilter(
      @Lazy AuthenticationManager authenticationManager,
      @Value("${orazaka.dev.admin-id:550e8400-e29b-41d4-a716-446655440001}")
          String devAdminUserId) {
    this.authenticationManager =
        Objects.requireNonNull(authenticationManager, "AuthenticationManager cannot be null");
    this.devAdminUserId = devAdminUserId;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer user-mock")) {
      try {
        Authentication auth =
            authenticationManager.authenticate(new BearerTokenAuthenticationToken(devAdminUserId));
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (AuthenticationException e) {
        MOCK_AUTH_LOGGER.debug("Failed to authenticate development mock user", e);
      }
    }
    filterChain.doFilter(request, response);
  }
}
