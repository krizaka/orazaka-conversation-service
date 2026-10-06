package com.orazaka.conversationservice.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.conversationservice.application.service.UserDirectoryService;
import com.orazaka.identity.domain.model.User;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.web.cors.CorsConfiguration;

class SecurityConfigTest {

  private UserDirectoryService userDirectoryService;
  private CorsProperties corsProperties;
  private Filter operationGraphFilter;
  private Filter rateLimitFilter;
  private JwtDecoder identityJwtDecoder;
  private SecurityConfig securityConfig;

  @BeforeEach
  void setUp() {
    userDirectoryService = mock(UserDirectoryService.class);
    identityJwtDecoder = mock(JwtDecoder.class);
    corsProperties = mock(CorsProperties.class);
    operationGraphFilter = mock(Filter.class);
    rateLimitFilter = mock(Filter.class);
    securityConfig =
        new SecurityConfig(
            userDirectoryService,
            identityJwtDecoder,
            corsProperties,
            operationGraphFilter,
            Optional.of(rateLimitFilter));
  }

  /** Stubs the decoder to accept {@code token} as a session JWT with the given subject. */
  private void stubJwt(String token, String subject) {
    Jwt jwt =
        Jwt.withTokenValue(token)
            .header("alg", "HS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    when(identityJwtDecoder.decode(token)).thenReturn(jwt);
  }

  @Test
  void testAuthenticationManagerSuccess() {
    String token = "valid-session-jwt";
    UUID id = UUID.randomUUID();
    User user = new User(id, "testuser", "test@example.class", true, Set.of("ROLE_USER"), Map.of());
    stubJwt(token, id.toString());
    when(userDirectoryService.getUser(id.toString())).thenReturn(user);

    AuthenticationManager manager = securityConfig.authenticationManager();
    Authentication authInput = new BearerTokenAuthenticationToken(token);
    Authentication authResult = manager.authenticate(authInput);

    assertNotNull(authResult);
    assertTrue(authResult instanceof UsernamePasswordAuthenticationToken);
    assertEquals(user, authResult.getPrincipal());
    assertEquals(token, authResult.getCredentials());
    assertEquals(1, authResult.getAuthorities().size());
    assertEquals("ROLE_USER", authResult.getAuthorities().iterator().next().getAuthority());
  }

  @Test
  void testAuthenticationManagerNonJwtTokenRejected() {
    // Legacy user-id tokens died at the Phase 2 cutover: a session token MUST be a JWT.
    String token = "550e8400-e29b-41d4-a716-446655440001";
    when(identityJwtDecoder.decode(token)).thenThrow(new BadJwtException("not a jwt"));

    AuthenticationManager manager = securityConfig.authenticationManager();
    Authentication authInput = new BearerTokenAuthenticationToken(token);

    assertThrows(BadCredentialsException.class, () -> manager.authenticate(authInput));
    verify(userDirectoryService, never()).getUser(anyString());
  }

  @Test
  void testAuthenticationManagerUserDisabled() {
    String token = "valid-session-jwt";
    UUID id = UUID.randomUUID();
    User user =
        new User(id, "testuser", "test@example.class", false, Set.of("ROLE_USER"), Map.of());
    stubJwt(token, id.toString());
    when(userDirectoryService.getUser(id.toString())).thenReturn(user);

    AuthenticationManager manager = securityConfig.authenticationManager();
    Authentication authInput = new BearerTokenAuthenticationToken(token);

    assertThrows(BadCredentialsException.class, () -> manager.authenticate(authInput));
  }

  @Test
  void testAuthenticationManagerExceptionThrown() {
    String token = "valid-session-jwt";
    UUID id = UUID.randomUUID();
    stubJwt(token, id.toString());
    when(userDirectoryService.getUser(id.toString()))
        .thenThrow(new RuntimeException("Database down"));

    AuthenticationManager manager = securityConfig.authenticationManager();
    Authentication authInput = new BearerTokenAuthenticationToken(token);

    assertThrows(BadCredentialsException.class, () -> manager.authenticate(authInput));
  }

  @Test
  void testAuthenticationManagerUnsupportedToken() {
    AuthenticationManager manager = securityConfig.authenticationManager();
    Authentication authInput = mock(Authentication.class);

    assertThrows(ProviderNotFoundException.class, () -> manager.authenticate(authInput));
  }

  @Test
  void testCorsConfigurationSourceNullOrigins() {
    when(corsProperties.allowedOrigins()).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> securityConfig.corsConfigurationSource());
  }

  @Test
  void testCorsConfigurationSourceEmptyOrigins() {
    when(corsProperties.allowedOrigins()).thenReturn(List.of());

    assertThrows(IllegalStateException.class, () -> securityConfig.corsConfigurationSource());
  }

  @Test
  void testCorsConfigurationSourceValid() {
    when(corsProperties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));
    when(corsProperties.allowedMethods()).thenReturn(List.of("GET", "POST"));
    when(corsProperties.allowedHeaders()).thenReturn(List.of("*"));
    when(corsProperties.allowCredentials()).thenReturn(true);

    var source = securityConfig.corsConfigurationSource();
    assertNotNull(source);

    // Test that resolving CORS configuration matches expectations
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setRequestURI("/api/v1/something");
    CorsConfiguration config = source.getCorsConfiguration(request);
    assertNotNull(config);
    assertEquals(List.of("http://localhost:3000"), config.getAllowedOrigins());
    assertEquals(List.of("GET", "POST"), config.getAllowedMethods());
    assertEquals(List.of("*"), config.getAllowedHeaders());
    assertTrue(config.getAllowCredentials());
  }
}
