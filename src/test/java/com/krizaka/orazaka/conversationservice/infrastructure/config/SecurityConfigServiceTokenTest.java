package com.krizaka.orazaka.conversationservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * A machine caller reaches {@code /internal/v1/**} with its service token (orazaka#301: the Studio
 * service asks this one to erase the memory of a person's turns), and a service token never becomes
 * a user.
 */
class SecurityConfigServiceTokenTest {

  private static final UUID ERIC = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");

  private final UserDirectoryClient directory = mock(UserDirectoryClient.class);
  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final AuthenticationManager manager =
      new SecurityConfig(directory, decoder, mock(Filter.class), Optional.empty())
          .authenticationManager();

  private static Jwt jwt(String subject, List<String> roles) {
    return Jwt.withTokenValue("t")
        .header("alg", "HS256")
        .subject(subject)
        .claim("roles", roles)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  @Test
  @DisplayName("a service token authenticates as SERVICE, with no user lookup")
  void aServiceTokenIsAServiceNotAUser() {
    when(decoder.decode("service")).thenReturn(jwt("orazaka-studio-service", List.of("SERVICE")));

    Authentication authenticated =
        manager.authenticate(new BearerTokenAuthenticationToken("service"));

    assertThat(authenticated.isAuthenticated()).isTrue();
    assertThat(authenticated.getPrincipal()).isEqualTo("orazaka-studio-service");
    assertThat(authenticated.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("SERVICE");
    verifyNoInteractions(directory);
  }

  @Test
  @DisplayName("a user token is still hydrated from the directory, with the user's authorities")
  void aUserTokenStaysAUser() {
    when(decoder.decode("user")).thenReturn(jwt(ERIC.toString(), List.of("ROLE_USER")));
    when(directory.getUser(ERIC.toString()))
        .thenReturn(
            new User(ERIC, "eric", "eric@example.com", true, Set.of("ROLE_USER"), Map.of()));

    Authentication authenticated = manager.authenticate(new BearerTokenAuthenticationToken("user"));

    assertThat(authenticated.getPrincipal()).isInstanceOf(User.class);
    assertThat(authenticated.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_USER");
  }

  @Test
  @DisplayName("a token that does not verify is refused")
  void anInvalidTokenIsRefused() {
    when(decoder.decode("forged")).thenThrow(new JwtException("bad signature"));

    assertThatThrownBy(() -> manager.authenticate(new BearerTokenAuthenticationToken("forged")))
        .isInstanceOf(BadCredentialsException.class);
  }
}
