package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.orazaka.conversationservice.application.service.CheckRateLimitService;
import com.krizaka.orazaka.conversationservice.application.service.ProductCheckService;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence.JdbcProductCheckAuditAdapter;
import com.krizaka.orazaka.core.application.checks.CheckRouter;
import com.krizaka.orazaka.core.domain.model.checks.CheckCategory;
import com.krizaka.orazaka.core.domain.model.checks.CheckOutcome;
import com.krizaka.orazaka.core.domain.model.checks.CheckProviderRow;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckRequest;
import com.krizaka.orazaka.core.domain.model.checks.CheckResult;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckMeter;
import com.krizaka.orazaka.core.domain.ports.outbound.ExternalCheckConnector;
import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import com.krizaka.users.domain.model.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code /api/v1/checks} (orazaka-ai-engine#105) over the real DDL of the audit: with and without a
 * signed principal, with and without the consent flag, a platform with no provider configured, a
 * pending check collected by its own tenant and by no other, and the rate limit.
 *
 * <p>The engine's one door is real; the provider is a fake that answers PENDING then MATCH, so the
 * deferred path is proved without a network. Standalone MockMvc as the other controller tests of
 * this service, on the shared singleton Postgres of {@link AbstractContainerIntegrationTest}.
 */
@SpringJUnitConfig(ChecksControllerIT.NoBeans.class)
class ChecksControllerIT extends AbstractContainerIntegrationTest {

  /** The shared base wires the container's ports into the Environment; no bean is needed. */
  @Configuration
  static class NoBeans {}

  @Autowired private Environment environment;

  private static final String MARIE = "11111111-1111-4111-8111-111111111111";
  private static final String PAUL = "22222222-2222-4222-8222-222222222222";
  private static final String SOMEONE = "33333333-3333-4333-8333-333333333333";
  private static final String BODY =
      """
      {"subjectId":"tenant-42","lastName":"Tremblay","firstName":"Ariane",
       "birthDate":"1990-04-12","region":"QC","consented":%s}""";

  private static JdbcTemplate jdbc;

  /** A provider that accepts a search and answers it on the poll. */
  private static final ExternalCheckConnector DEFERRED =
      new ExternalCheckConnector() {
        @Override
        public String provider() {
          return "deferred";
        }

        @Override
        public CheckResult verifyCourtRecords(CheckRequest request) {
          return CheckResult.answered(CheckOutcome.PENDING, "case-777");
        }

        @Override
        public CheckResult verifyCredit(CheckRequest request) {
          return CheckResult.answered(CheckOutcome.NO_MATCH, "credit-1");
        }

        @Override
        public CheckResult verifyIdentity(CheckRequest request) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CheckResult poll(CheckRequest request, CheckPurpose purpose, String providerRef) {
          return CheckResult.answered(CheckOutcome.MATCH, CheckCategory.CRIMINAL, providerRef);
        }
      };

  @BeforeEach
  void start() throws Exception {
    if (jdbc != null) {
      return;
    }
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password")));
    jdbc.execute("DROP TABLE IF EXISTS external_check_audit");
    jdbc.execute(Files.readString(Path.of("infra/migrations/2026-10-27-product-checks-door.sql")));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
    jdbc.execute("TRUNCATE external_check_audit");
  }

  @Test
  @DisplayName("no signed principal: 401, and nothing is asked or audited")
  void noPrincipalIs401() throws Exception {
    mvc(true, 30).perform(submit("court-records", true)).andExpect(status().isUnauthorized());
    assertThat(auditLines()).isZero();
  }

  @Test
  @DisplayName("a principal without an allowed role: 403")
  void noRoleIs403() throws Exception {
    authenticate(MARIE, "acme", "ROLE_USER");
    mvc(true, 30).perform(submit("court-records", true)).andExpect(status().isForbidden());
    assertThat(auditLines()).isZero();
  }

  @Test
  @DisplayName("no consent flag: REFUSED / NO_CONSENT, no provider asked, one audit line")
  void noConsentIsRefused() throws Exception {
    authenticate(MARIE, "acme", "ROLE_SERVICE");
    mvc(true, 30)
        .perform(submit("court-records", false))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("REFUSED"))
        .andExpect(jsonPath("$.refusal").value("NO_CONSENT"));
    assertThat(
            jdbc.queryForList("SELECT tenant_id, refusal, provider_ref FROM external_check_audit"))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line).containsEntry("tenant_id", "acme");
              assertThat(line).containsEntry("refusal", "NO_CONSENT");
              assertThat(line.get("provider_ref")).isNull();
            });
  }

  @Test
  @DisplayName("no provider configured: REFUSED / NOT_CONFIGURED, never a made-up result")
  void noProviderFailsClosed() throws Exception {
    authenticate(MARIE, "acme", "ROLE_SERVICE");
    mvc(false, 30)
        .perform(submit("court-records", true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("REFUSED"))
        .andExpect(jsonPath("$.refusal").value("NOT_CONFIGURED"))
        .andExpect(jsonPath("$.providerRef").doesNotExist());
  }

  @Test
  @DisplayName("consent given: PENDING with the provider's reference, audited under the tenant")
  void submitReturnsThePendingReference() throws Exception {
    authenticate(MARIE, "acme", "ROLE_SERVICE");
    mvc(true, 30)
        .perform(submit("court-records", true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.purpose").value("COURT_RECORDS"))
        .andExpect(jsonPath("$.outcome").value("PENDING"))
        .andExpect(jsonPath("$.providerRef").value("case-777"));
    assertThat(jdbc.queryForList("SELECT * FROM external_check_audit").toString())
        .contains("acme")
        .doesNotContain("Tremblay")
        .doesNotContain("Ariane")
        .doesNotContain("1990");
  }

  @Test
  @DisplayName("the tenant that submitted collects the answer; another tenant gets a 404")
  void collectIsScopedToTheTenant() throws Exception {
    authenticate(MARIE, "acme", "ROLE_SERVICE");
    MockMvc mvc = mvc(true, 30);
    mvc.perform(submit("court-records", true)).andExpect(status().isOk());

    // Another user of the same tenant collects it.
    authenticate(PAUL, "acme", "ROLE_SERVICE");
    mvc.perform(collect("case-777", "tenant-42", true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("MATCH"))
        .andExpect(jsonPath("$.category").value("CRIMINAL"));

    // The consent must still stand.
    mvc.perform(collect("case-777", "tenant-42", false))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refusal").value("NO_CONSENT"));

    // Another tenant never received this reference.
    authenticate(SOMEONE, "other", "ROLE_SERVICE");
    mvc.perform(collect("case-777", "tenant-42", true)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("over the limit: 429 with Retry-After")
  void rateLimited() throws Exception {
    authenticate(MARIE, "acme", "ROLE_SERVICE");
    MockMvc mvc = mvc(true, 2);
    mvc.perform(submit("credit", true)).andExpect(status().isOk());
    mvc.perform(submit("credit", true)).andExpect(status().isOk());
    mvc.perform(submit("credit", true))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  @Test
  @DisplayName("identity is not offered, and a subject without names is a 400")
  void unknownPurposeAndInvalidSubject() throws Exception {
    authenticate(MARIE, "acme", "ROLE_SERVICE");
    MockMvc mvc = mvc(true, 30);
    mvc.perform(submit("identity", true)).andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/checks/credit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":\"s\",\"consented\":true}"))
        .andExpect(status().isBadRequest());
  }

  private static long auditLines() {
    Long count = jdbc.queryForObject("SELECT count(*) FROM external_check_audit", Long.class);
    return count == null ? 0 : count;
  }

  private static org.springframework.test.web.servlet.RequestBuilder submit(
      String purpose, boolean consented) {
    return post("/api/v1/checks/" + purpose)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY.formatted(consented));
  }

  private static org.springframework.test.web.servlet.RequestBuilder collect(
      String providerRef, String subjectId, boolean consented) {
    return get("/api/v1/checks/" + providerRef)
        .param("subjectId", subjectId)
        .param("consented", Boolean.toString(consented));
  }

  private static MockMvc mvc(boolean configured, int perMinute) {
    CheckProviderRow row =
        new CheckProviderRow(
            "deferred",
            "deferred",
            EnumSet.allOf(CheckPurpose.class),
            Set.of(),
            1,
            true,
            "https://provider.example.test",
            0);
    CheckMeter.Hold hold =
        new CheckMeter.Hold() {
          @Override
          public void settle() {
            // the test meters nothing: no hold to settle
          }

          @Override
          public void release(String reason) {
            // the test meters nothing: no hold to release
          }
        };
    ProductCheckService service =
        new ProductCheckService(
            new CheckRouter(
                () -> configured ? List.of(row) : List.<CheckProviderRow>of(),
                Map.of("deferred", DEFERRED)),
            new com.krizaka.orazaka.core.domain.ports.outbound.CheckKeyStore() {
              @Override
              public Optional<String> userKey(String userId, String provider) {
                return Optional.empty();
              }

              @Override
              public Optional<String> platformKey(String provider) {
                return Optional.of("platform-key");
              }
            },
            (runId, actorId, provider, purpose) -> hold,
            () -> Optional.of(new byte[32]),
            new JdbcProductCheckAuditAdapter(jdbc),
            new CheckRateLimitService(perMinute, Clock.systemUTC()),
            Set.of("ROLE_ADMIN", "ROLE_SERVICE"),
            Clock.systemUTC());
    return MockMvcBuilders.standaloneSetup(new ChecksController(service))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .build();
  }

  /** Puts one signed-in user, of one tenant, in the security context. */
  private static void authenticate(String id, String tenant, String role) {
    Set<String> authorities = Set.of(role, "TENANT_" + tenant);
    User user =
        new User(
            UUID.fromString(id),
            "actor-" + id,
            id + "@orazaka.test",
            true,
            authorities,
            Map.of(),
            List.of());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            user, "token", authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    SecurityContextHolder.setContext(context);
  }
}
