package com.krizaka.orazaka.conversationservice.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The service's real filter chain reads a bearer token from the {@code Authorization} header only:
 * the same valid token in the query string authenticates nothing (URLs reach access logs, proxies
 * and browser history, a header does not).
 */
@SpringJUnitWebConfig(BearerTokenLocationTest.Context.class)
class BearerTokenLocationTest {

  private static final String TOKEN = "a-valid-session-jwt";
  private static final UUID USER_ID = UUID.randomUUID();

  @Autowired WebApplicationContext context;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
            .build();
  }

  @Test
  void theHeaderAuthenticates() throws Exception {
    mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk());
  }

  @Test
  void theQueryStringDoesNot() throws Exception {
    mvc.perform(get("/api/v1/probe").queryParam("access_token", TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Configuration
  @EnableWebMvc
  @Import(SecurityConfig.class)
  static class Context {

    @Bean
    UserDirectoryClient userDirectoryClient() {
      UserDirectoryClient directory = mock(UserDirectoryClient.class);
      when(directory.getUser(USER_ID.toString()))
          .thenReturn(
              new User(
                  USER_ID, "person", "person@example.test", true, Set.of("ROLE_USER"), Map.of()));
      return directory;
    }

    @Bean
    JwtDecoder sessionJwtDecoder() {
      return token -> {
        if (!TOKEN.equals(token)) {
          throw new BadJwtException("not a session token");
        }
        return Jwt.withTokenValue(token)
            .header("alg", "HS256")
            .subject(USER_ID.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
      };
    }

    @Bean
    Filter operationGraphFilter() {
      return (request, response, chain) -> chain.doFilter(request, response);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
      return new UrlBasedCorsConfigurationSource();
    }

    @Bean
    Probe probe() {
      return new Probe();
    }
  }

  @RestController
  static class Probe {

    @GetMapping("/api/v1/probe")
    String ok() {
      return "ok";
    }
  }
}
