package com.krizaka.orazaka.conversationservice.infrastructure.config;

import com.krizaka.security.web.SecurityBaseline;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import jakarta.servlet.Filter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless Spring Security configuration for the conversation service.
 *
 * <p>Defines the security filter chain and request authorization rules. CORS is krizaka-web's:
 * {@code krizaka.web.cors.allowed-origins} declares the origins, and its {@code
 * corsConfigurationSource} is the one {@code cors()} picks up. The chain starts from the Krizaka
 * {@link SecurityBaseline} (stateless, preflight/health/info/error open, {@code SERVICE}-only
 * {@code /internal/v1/**}) and adds this service's own rules; every incoming request is
 * authenticated on-the-fly via official Spring Security OAuth2 resource server filters.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private static final String ADMIN = "ROLE_ADMIN";
  private static final String USER = "ROLE_USER";
  private static final String SERVICE = "SERVICE";

  private final UserDirectoryClient userDirectoryService;
  private final JwtDecoder identityJwtDecoder;
  private final Filter operationGraphFilter;
  private final Optional<Filter> rateLimitFilter;

  public SecurityConfig(
      UserDirectoryClient userDirectoryService,
      @Qualifier("sessionJwtDecoder") JwtDecoder identityJwtDecoder,
      @Qualifier("operationGraphFilter") Filter operationGraphFilter,
      @Qualifier("rateLimitFilter") Optional<Filter> rateLimitFilter) {
    this.userDirectoryService = userDirectoryService;
    this.identityJwtDecoder = identityJwtDecoder;
    this.operationGraphFilter = operationGraphFilter;
    this.rateLimitFilter = rateLimitFilter;
  }

  @Bean
  public AuthenticationManager authenticationManager() {
    return authentication -> {
      if (authentication instanceof BearerTokenAuthenticationToken bearerToken) {
        String token = bearerToken.getToken();
        Jwt jwt = decode(token);
        if (isServiceToken(jwt)) {
          // A machine caller (another Orazaka service: the Studio service asking for an erasure,
          // orazaka#301) has no user row to hydrate. Its principal is its own name and its only
          // authority is SERVICE: the baseline reserves /internal/v1/** for exactly that, and every
          // user route asks for ADMIN or USER, which this token does not carry.
          return new UsernamePasswordAuthenticationToken(
              jwt.getSubject(), token, List.of(new SimpleGrantedAuthority(SERVICE)));
        }
        User user = hydrate(jwt.getSubject());
        if (!user.enabled()) {
          throw new BadCredentialsException("Invalid or inactive session token");
        }
        var authorities = user.authorities().stream().map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken(user, token, authorities);
      }
      throw new ProviderNotFoundException("Unsupported authentication token type");
    };
  }

  /**
   * Decodes a bearer token as a session JWT. The token must be one — {@code oz_} API keys are
   * exchanged for JWTs at the edge, so the router never sees them. The signature is verified
   * locally (shared HS256 secret).
   */
  private Jwt decode(String token) {
    try {
      return identityJwtDecoder.decode(token);
    } catch (JwtException ex) {
      throw new BadCredentialsException("Invalid or inactive session token");
    }
  }

  /** Whether the token is a service-to-service one: {@code roles} carries {@code SERVICE}. */
  private static boolean isServiceToken(Jwt jwt) {
    Object roles = jwt.getClaim("roles");
    return roles instanceof Collection<?> granted && granted.contains(SERVICE);
  }

  /** The principal of a user token, hydrated by {@code sub} through the cached user directory. */
  private User hydrate(String userId) {
    try {
      return userDirectoryService.getUser(userId);
    } catch (RuntimeException ex) {
      throw new BadCredentialsException("Invalid or inactive session token");
    }
  }

  @Bean
  @SuppressWarnings("java:S4502") // Justified: CSRF disabled for stateless API.
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, AuthenticationManager authenticationManager) throws Exception {
    var resolver = new DefaultBearerTokenResolver();
    resolver.setAllowUriQueryParameter(true);

    // Justified: Stateless OAuth2 Resource Server — no cookies, no session state.
    // CSRF protection is not applicable for token-based (Bearer) authentication.
    // See AGENTS.md §7.1: "CSRF disabled for stateless API."
    SecurityBaseline.apply(
            http,
            auth ->
                auth.requestMatchers("/api/v1/status/health")
                    .permitAll()
                    // The apps' version contract (krizaka-app-config): the config is public, so
                    // an old build with an expired session still learns it must update; its
                    // parameters are edited by administrators only.
                    .requestMatchers("/api/v1/app-config")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/app-config/**")
                    .hasAuthority(ADMIN)
                    .requestMatchers("/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/register")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/verify")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/oauth")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/forgot")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/reset")
                    .permitAll()
                    .requestMatchers("/api/v1/status/graph")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/features")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/assets/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/chat/stream/**")
                    .hasAnyAuthority(ADMIN, USER)
                    // A spoken sentence is fetched by an <audio> element, which sends no bearer
                    // token: its signed, five-minute URL is the authorisation (ADR-076).
                    .requestMatchers("/api/v1/voice/clips/**")
                    .permitAll()
                    .requestMatchers("/api/v1/voice/turns")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/credentials/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/api-keys/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/jobs/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/models")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/embeddings")
                    .hasAnyAuthority(ADMIN, USER)
                    // The law read API (#89): a person's session, or an oz_ key the edge
                    // exchanged for one; never a bare service token.
                    .requestMatchers("/api/v1/law/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/intent/route")
                    .permitAll())
        .cors(Customizer.withDefaults())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .bearerTokenResolver(resolver)
                    .opaqueToken(opaque -> opaque.authenticationManager(authenticationManager)));

    http.addFilterAfter(operationGraphFilter, BearerTokenAuthenticationFilter.class);
    rateLimitFilter.ifPresent(
        filter -> http.addFilterAfter(filter, BearerTokenAuthenticationFilter.class));

    return http.build();
  }
}
