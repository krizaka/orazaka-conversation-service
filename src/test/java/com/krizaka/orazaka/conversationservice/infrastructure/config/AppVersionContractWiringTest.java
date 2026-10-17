package com.krizaka.orazaka.conversationservice.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.appconfig.KrizakaAppConfigAutoConfiguration;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The apps' version contract (krizaka-app-config) behind THIS service's real security chain: the
 * config is public, its admin API is administrators-only, and an app below the minimum learns it
 * must update (426) before authentication decides anything — an old build with an expired session
 * still gets the screen it needs.
 */
@SpringJUnitWebConfig(AppVersionContractWiringTest.Wiring.class)
@TestPropertySource(properties = "krizaka.app-config.store=memory")
class AppVersionContractWiringTest {

  private static final String BODY =
      """
      {"minSupportedVersion":"1.2.0","latestVersion":"1.4.0","updateOverride":null,
       "storeUrl":"https://apps.apple.com/app/id1","maintenanceOn":false,"maintenanceMessage":{},
       "message":{"en":"Update"},"contractMin":1,"contractMax":2,"contractSunset":null,"flags":{},
       "reason":"ship 1.4"}
      """;

  /** The collaborators of {@link SecurityConfig}, stubbed; everything else is the real thing. */
  @Configuration
  @EnableWebMvc
  @Import(SecurityConfig.class)
  @ImportAutoConfiguration({
    JacksonAutoConfiguration.class,
    KrizakaAppConfigAutoConfiguration.class
  })
  static class Wiring {

    @Bean
    UserDirectoryClient userDirectoryClient() {
      return mock(UserDirectoryClient.class);
    }

    @Bean("sessionJwtDecoder")
    JwtDecoder sessionJwtDecoder() {
      return mock(JwtDecoder.class);
    }

    @Bean("operationGraphFilter")
    Filter operationGraphFilter() {
      return (request, response, chain) -> chain.doFilter(request, response);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired UserDirectoryClient users;
  @Autowired JwtDecoder jwt;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  Filter securityChain;

  @Autowired FilterRegistrationBean<? extends Filter> krizakaClientVersionFilter;

  MockMvc mvc;

  @BeforeEach
  void setUp() {
    // The version filter runs before the security chain, as the servlet container orders them.
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(krizakaClientVersionFilter.getFilter(), securityChain)
            .build();
  }

  private void signIn(String token, Set<String> roles) {
    UUID id = UUID.randomUUID();
    when(jwt.decode(token))
        .thenReturn(
            Jwt.withTokenValue(token)
                .header("alg", "HS256")
                .subject(id.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
    when(users.getUser(id.toString()))
        .thenReturn(new User(id, "u", "u@example.com", true, roles, Map.of()));
  }

  @Test
  @DisplayName("the config is public: an anonymous app can always learn it must update")
  void theConfigIsPublic() throws Exception {
    mvc.perform(get("/api/v1/app-config").param("platform", "ios").param("version", "1.0.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platform").value("ios"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
  }

  @Test
  @DisplayName("its admin API is administrators-only")
  void theAdminApiIsForAdministrators() throws Exception {
    mvc.perform(get("/api/v1/admin/app-config")).andExpect(status().isUnauthorized());

    signIn("user-token", Set.of("ROLE_USER"));
    mvc.perform(get("/api/v1/admin/app-config").header("Authorization", "Bearer user-token"))
        .andExpect(status().isForbidden());

    signIn("admin-token", Set.of("ROLE_ADMIN"));
    mvc.perform(get("/api/v1/admin/app-config").header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/v1/admin/app-config/ios")
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
  }

  @Test
  @DisplayName("a published minimum answers 426 before authentication, and N-1 keeps working")
  void anOldAppGets426BeforeAuthentication() throws Exception {
    signIn("admin-token", Set.of("ROLE_ADMIN"));
    mvc.perform(
            put("/api/v1/admin/app-config/android")
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    BODY.replace(
                        "apps.apple.com/app/id1",
                        "play.google.com/store/apps/details?id=com.krizaka.orazaka")))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/jobs")
                .header("X-Client-Platform", "android")
                .header("X-Client-Version", "1.1.0"))
        .andExpect(status().isUpgradeRequired())
        .andExpect(jsonPath("$.code").value("upgrade-required"))
        .andExpect(jsonPath("$.appConfig.updateMode").value("force"))
        .andExpect(
            jsonPath("$.appConfig.storeUrl")
                .value(org.hamcrest.Matchers.containsString("play.google.com")));

    // A current app on the previous contract is not refused by the guard; the chain then asks it to
    // sign in.
    mvc.perform(
            get("/api/v1/jobs")
                .header("X-Client-Platform", "android")
                .header("X-Client-Version", "1.3.0")
                .header("X-Contract-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Deprecation", "true"));
  }

  @Test
  @DisplayName("a browser, which sends none of the headers, is not guarded")
  void aBrowserPassesTheGuard() throws Exception {
    mvc.perform(get("/api/v1/jobs")).andExpect(status().isUnauthorized());
  }
}
