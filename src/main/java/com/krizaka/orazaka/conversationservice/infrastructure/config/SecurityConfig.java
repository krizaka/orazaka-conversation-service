package com.krizaka.orazaka.conversationservice.infrastructure.config;

import com.krizaka.security.web.SecurityBaseline;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import jakarta.servlet.Filter;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless Spring Security configuration for the conversation service.
 *
 * <p>Defines the security filter chain, CORS policy, and request authorization rules. The chain
 * starts from the Krizaka {@link SecurityBaseline} (stateless, preflight/health/info/error open,
 * {@code SERVICE}-only {@code /internal/v1/**}) and adds this service's own rules; every incoming
 * request is authenticated on-the-fly via official Spring Security OAuth2 resource server filters.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private static final String ADMIN = "ROLE_ADMIN";
  private static final String USER = "ROLE_USER";

  private final UserDirectoryClient userDirectoryService;
  private final JwtDecoder identityJwtDecoder;
  private final CorsProperties corsProperties;
  private final Filter operationGraphFilter;
  private final Optional<Filter> rateLimitFilter;

  public SecurityConfig(
      UserDirectoryClient userDirectoryService,
      @Qualifier("sessionJwtDecoder") JwtDecoder identityJwtDecoder,
      CorsProperties corsProperties,
      @Qualifier("operationGraphFilter") Filter operationGraphFilter,
      @Qualifier("rateLimitFilter") Optional<Filter> rateLimitFilter) {
    this.userDirectoryService = userDirectoryService;
    this.identityJwtDecoder = identityJwtDecoder;
    this.corsProperties = corsProperties;
    this.operationGraphFilter = operationGraphFilter;
    this.rateLimitFilter = rateLimitFilter;
  }

  @Bean
  public AuthenticationManager authenticationManager() {
    return authentication -> {
      if (authentication instanceof BearerTokenAuthenticationToken bearerToken) {
        String token = bearerToken.getToken();
        User user = resolvePrincipal(token);
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
   * Resolves a bearer token to its principal. The token must be a session JWT — {@code oz_} API
   * keys are exchanged for JWTs at the edge, so the router never sees them. The signature is
   * verified locally (shared HS256 secret); the principal is hydrated by {@code sub} through the
   * cached user directory (the identity service's internal API).
   */
  private User resolvePrincipal(String token) {
    String userId;
    try {
      userId = identityJwtDecoder.decode(token).getSubject();
    } catch (JwtException ex) {
      throw new BadCredentialsException("Invalid or inactive session token");
    }
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
                    .requestMatchers("/api/v1/jobs/*/progress")
                    .permitAll()
                    .requestMatchers("/api/v1/chat/stream/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/credentials/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/api-keys/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/jobs/**")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/models")
                    .hasAnyAuthority(ADMIN, USER)
                    .requestMatchers("/api/v1/intent/route")
                    .permitAll())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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

  @Bean
  @SuppressWarnings(
      "java:S5122") // Justified: CORS policy is dynamically configured via properties and allows
  // wildcard path mapping for APIs.
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    if (corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isEmpty()) {
      throw new IllegalStateException(
          "CORS allowed origins are unresolved. Configure orazaka.cors.allowed-origins.");
    }
    configuration.setAllowedOrigins(corsProperties.allowedOrigins());
    configuration.setAllowedMethods(corsProperties.allowedMethods());
    configuration.setAllowedHeaders(corsProperties.allowedHeaders());
    configuration.setAllowCredentials(corsProperties.allowCredentials());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
