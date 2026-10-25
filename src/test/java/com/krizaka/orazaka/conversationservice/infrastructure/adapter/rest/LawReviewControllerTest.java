package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.orazaka.conversationservice.infrastructure.config.SecurityConfig;
import com.krizaka.orazaka.tools.application.port.law.LawSettings;
import com.krizaka.orazaka.tools.application.service.KindDemandService;
import com.krizaka.orazaka.tools.application.service.LawCorpusCatalogueService;
import com.krizaka.orazaka.tools.application.service.LawManualImportService;
import com.krizaka.orazaka.tools.application.service.RuleProposalReviewService;
import com.krizaka.orazaka.tools.application.service.RuleProposalService;
import com.krizaka.orazaka.tools.application.service.RulesetPromotionService;
import com.krizaka.orazaka.tools.domain.model.law.*;
import com.krizaka.orazaka.tools.domain.model.law.LawReviewRefusal.Code;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import com.krizaka.web.KrizakaWebProperties;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The admin law API of ADR-089 §8 behind this service's real security chain and its operator audit:
 * a non-administrator gets 403 and reaches no use case; every write leaves one {@code
 * operator_audit} line, refused ones included; promotion's refusals keep their code.
 */
@SpringJUnitWebConfig(LawReviewControllerTest.Wiring.class)
class LawReviewControllerTest {

  private static final String BASE = "/api/v1/admin/law";
  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final String KIND = "qc-visit-notice";
  private static final String HASH = "a".repeat(64);
  private static final String PR = "https://github.com/krizaka/orazaka-packs/pull/4242";

  /** The audit lines written, in memory. */
  static final List<OperatorAuditStore.Line> AUDIT = new ArrayList<>();

  @Configuration
  @EnableWebMvc
  @Import({SecurityConfig.class, LawReviewController.class, LawCorpusAdminController.class})
  @ImportAutoConfiguration(JacksonAutoConfiguration.class)
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

    @Bean
    RuleProposalService ruleProposalService() {
      return mock(RuleProposalService.class);
    }

    @Bean
    RuleProposalReviewService ruleProposalReviewService() {
      return mock(RuleProposalReviewService.class);
    }

    @Bean
    RulesetPromotionService rulesetPromotionService() {
      return mock(RulesetPromotionService.class);
    }

    @Bean
    KindDemandService kindDemandService() {
      return mock(KindDemandService.class);
    }

    @Bean
    LawCorpusCatalogueService lawCorpusCatalogueService() {
      return mock(LawCorpusCatalogueService.class);
    }

    @Bean
    LawManualImportService lawManualImportService() {
      return mock(LawManualImportService.class);
    }

    @Bean
    LawSettings lawSettings() {
      return mock(LawSettings.class);
    }

    @Bean
    OperatorAuditFilter operatorAuditFilter() {
      return new OperatorAuditFilter(
          new OperatorAuditStore() {
            @Override
            public void append(Line line) {
              AUDIT.add(line);
            }

            @Override
            public List<Stored> recent(int limit) {
              return List.of();
            }
          });
    }

    @Bean
    ProblemDetailsAdvice problemDetailsAdvice() {
      return new ProblemDetailsAdvice(new KrizakaWebProperties(null, null));
    }

    @Bean
    RestErrorResolver restErrorResolver(ProblemDetailsAdvice problems) {
      return new RestErrorResolver(problems);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired UserDirectoryClient users;
  @Autowired JwtDecoder jwt;
  @Autowired RuleProposalService proposals;
  @Autowired RuleProposalReviewService review;
  @Autowired RulesetPromotionService promotion;
  @Autowired KindDemandService demand;
  @Autowired LawCorpusCatalogueService catalogue;
  @Autowired LawManualImportService imports;
  @Autowired LawSettings settings;
  @Autowired OperatorAuditFilter audit;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  Filter securityChain;

  MockMvc mvc;
  UUID admin;
  UUID member;

  @BeforeEach
  void setUp() {
    reset(proposals, review, promotion, demand, catalogue, imports, settings);
    AUDIT.clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain, audit).build();
    admin = UUID.randomUUID();
    member = UUID.randomUUID();
    session("admin-session", admin, "ROLE_ADMIN", "eric@example.test");
    session("member-session", member, "ROLE_USER", "member@example.test");
  }

  private void session(String token, UUID id, String role, String email) {
    when(jwt.decode(token))
        .thenReturn(
            Jwt.withTokenValue(token)
                .header("alg", "HS256")
                .subject(id.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
    when(users.getUser(id.toString()))
        .thenReturn(new User(id, "eric", email, true, Set.of(role), Map.of()));
  }

  private static ProposedRule rule() {
    return new ProposedRule(
        "QC-VISIT-001",
        "Préavis de 24 heures",
        "MAJOR",
        "DETERMINISTIC",
        "(?i)visite",
        new RuleCheck("REQUIRED_PATTERN", "(?i)24\\s*heures"),
        null,
        "C.c.Q., art. 1931",
        "https://www.legisquebec.gouv.qc.ca/fr/version/lc/CCQ-1991?code=se:1931",
        "Le préavis de 24 heures est donné.",
        "Le préavis de 24 heures manque.",
        new RuleFixtures(List.of("un préavis de 24 heures"), List.of("un préavis d'une heure")),
        List.of(new LawBlock("CCQ-1991", "1931", "fr", HASH, "un préavis de 24 heures")));
  }

  private static RuleProposal proposal(ProposalStatus status) {
    return new RuleProposal(
        ID,
        "QC",
        KIND,
        "QC-VISIT-001",
        1,
        rule(),
        UUID.fromString("00000000-0000-4000-8000-000000000089"),
        status,
        new VetReport("1", "2026-10-20", List.of()),
        status == ProposalStatus.PROPOSED ? null : "eric-admin",
        status == ProposalStatus.PROPOSED ? null : Instant.parse("2026-10-24T12:00:00Z"),
        null,
        null,
        Instant.parse("2026-10-24T11:00:00Z"));
  }

  private static RequestBuilder as(String token, RequestBuilder request) {
    return servletContext -> {
      var built = request.buildRequest(servletContext);
      built.addHeader("Authorization", "Bearer " + token);
      return built;
    };
  }

  /** Every write of the API, with a body it accepts. */
  static List<RequestBuilder> writes() {
    String id = ID.toString();
    return List.of(
        put(BASE + "/proposals/" + id)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ruleId\":\"QC-VISIT-001\",\"title\":\"t\"}"),
        post(BASE + "/proposals/" + id + "/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ok\"}"),
        post(BASE + "/proposals/" + id + "/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"no\"}"),
        post(BASE + "/kinds/" + KIND + "/promote")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"proposalIds\":[\"" + id + "\"],\"effectiveFrom\":\"1999-04-01\"}"),
        post(BASE + "/derivations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"jurisdiction\":\"QC\",\"kindId\":\"" + KIND + "\",\"asOf\":\"2026-10-20\"}"),
        multipart(BASE + "/imports")
            .file(new MockMultipartFile("file", "ccq.xml", "application/xml", "<x/>".getBytes()))
            .param("jurisdiction", "QC")
            .param("instrument", "CCQ-1991")
            .param("language", "fr")
            .param("datasetUrl", "https://www.donneesquebec.ca/ccq.xml"));
  }

  /** Every read of the API. */
  static List<RequestBuilder> reads() {
    return List.of(
        get(BASE + "/proposals"),
        get(BASE + "/proposals/" + ID),
        get(BASE + "/kinds/" + KIND + "/golden"),
        get(BASE + "/demand"),
        get(BASE + "/instruments"),
        get(BASE + "/imports"),
        get(
            BASE
                + "/articles?jurisdiction=QC&instrument=CCQ-1991&ref=1931&language=fr&asOf=2026-10-20"));
  }

  static List<RequestBuilder> everyRoute() {
    List<RequestBuilder> all = new ArrayList<>(writes());
    all.addAll(reads());
    return all;
  }

  @ParameterizedTest
  @MethodSource("everyRoute")
  @DisplayName("a non-admin gets 403 on every route and reaches no use case")
  void aMemberIsForbidden(RequestBuilder request) throws Exception {
    mvc.perform(as("member-session", request)).andExpect(status().isForbidden());

    verifyNoInteractions(proposals, review, promotion, demand, catalogue, imports);
  }

  @ParameterizedTest
  @MethodSource("everyRoute")
  void noSessionIsUnauthorized(RequestBuilder request) throws Exception {
    mvc.perform(request).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @MethodSource("writes")
  @DisplayName("every write leaves one operator_audit line, whatever its outcome")
  void everyWriteIsAudited(RequestBuilder request) throws Exception {
    mvc.perform(as("admin-session", request));

    assertThat(AUDIT).hasSize(1);
    OperatorAuditStore.Line line = AUDIT.getFirst();
    assertThat(line.actorId()).isEqualTo(admin.toString());
    assertThat(line.actorLabel()).isEqualTo("eric@example.test");
    assertThat(line.action()).startsWith("P").contains("/api/v1/admin/law/");
  }

  @ParameterizedTest
  @MethodSource("reads")
  void aReadIsNotAudited(RequestBuilder request) throws Exception {
    mvc.perform(as("admin-session", request));

    assertThat(AUDIT).isEmpty();
  }

  @Test
  void theQueueListsProposalsWithTheirQuoteFixturesAndVetReport() throws Exception {
    when(proposals.list(ProposalStatus.PROPOSED, KIND))
        .thenReturn(List.of(proposal(ProposalStatus.PROPOSED)));

    mvc.perform(as("admin-session", get(BASE + "/proposals?status=proposed&kindId=" + KIND)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(ID.toString()))
        .andExpect(jsonPath("$[0].status").value("PROPOSED"))
        .andExpect(jsonPath("$[0].rule.law[0].quote").value("un préavis de 24 heures"))
        .andExpect(jsonPath("$[0].rule.sourceUrl").value(rule().sourceUrl()))
        .andExpect(jsonPath("$[0].rule.fixtures.fail[0]").value("un préavis d'une heure"))
        .andExpect(jsonPath("$[0].vetReport.passed").value(true))
        .andExpect(jsonPath("$[0].vetReport.asOf").value("2026-10-20"));
  }

  @Test
  void anUnknownStatusIsAnInvalidRequest() throws Exception {
    mvc.perform(as("admin-session", get(BASE + "/proposals?status=MAYBE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
  }

  @Test
  void approveRecordsTheAdministratorAndIsAuditedOnItsTarget() throws Exception {
    when(proposals.approve(ID, admin.toString(), "read beside art. 1931"))
        .thenReturn(proposal(ProposalStatus.APPROVED));

    mvc.perform(
            as(
                "admin-session",
                post(BASE + "/proposals/" + ID + "/approve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\":\"read beside art. 1931\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    OperatorAuditStore.Line line = AUDIT.getFirst();
    assertThat(line.action()).isEqualTo("POST /api/v1/admin/law/proposals/{id}/approve");
    assertThat(line.targetId()).isEqualTo(ID.toString());
    assertThat(line.outcome()).isEqualTo("ok");
  }

  @Test
  void aRejectionWithoutAReasonIsRefusedAndStillAudited() throws Exception {
    when(proposals.reject(eq(ID), anyString(), isNull()))
        .thenThrow(new IllegalArgumentException("a rejection says why"));

    mvc.perform(as("admin-session", post(BASE + "/proposals/" + ID + "/reject")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));

    assertThat(AUDIT.getFirst().outcome()).isEqualTo("refused");
  }

  @Test
  void aDecisionOnAProposalNotWaitingIsAConflict() throws Exception {
    when(proposals.approve(eq(ID), anyString(), any()))
        .thenThrow(new LawReviewRefusal(Code.PROPOSAL_NOT_REVIEWABLE, "already approved"));

    mvc.perform(as("admin-session", post(BASE + "/proposals/" + ID + "/approve")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("proposal-not-reviewable"));
  }

  @Test
  void anEditIsTheNextRevision() throws Exception {
    RuleProposal next =
        new RuleProposal(
            UUID.randomUUID(),
            "QC",
            KIND,
            "QC-VISIT-001",
            2,
            rule(),
            null,
            ProposalStatus.PROPOSED,
            new VetReport("1", "2026-10-20", List.of()),
            null,
            null,
            null,
            null,
            Instant.parse("2026-10-24T12:30:00Z"));
    when(review.edit(eq(ID), any(ProposedRule.class))).thenReturn(next);

    mvc.perform(
            as(
                "admin-session",
                put(BASE + "/proposals/" + ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"ruleId\":\"QC-VISIT-001\",\"title\":\"Préavis\",\"law\":[{\"instrument\":"
                            + "\"CCQ-1991\",\"ref\":\"1931\",\"language\":\"fr\",\"contentHash\":\""
                            + HASH
                            + "\",\"quote\":\"un préavis de 24 heures\"}]}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proposalRevision").value(2))
        .andExpect(jsonPath("$.status").value("PROPOSED"));
  }

  @Test
  @DisplayName("promote refuses an unapproved proposal: 409 proposal-not-approved, audited refused")
  void promoteRefusesAnUnapprovedProposal() throws Exception {
    when(promotion.promote(eq(KIND), anyList(), any(), any()))
        .thenThrow(new LawReviewRefusal(Code.PROPOSAL_NOT_APPROVED, ID + " is PROPOSED"));

    mvc.perform(as("admin-session", writes().get(3)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("proposal-not-approved"));

    assertThat(AUDIT.getFirst().action()).isEqualTo("POST /api/v1/admin/law/kinds/{id}/promote");
    assertThat(AUDIT.getFirst().targetId()).isEqualTo(KIND);
    assertThat(AUDIT.getFirst().outcome()).isEqualTo("refused");
  }

  @Test
  @DisplayName("promote refuses a kind below the golden minimum: 409 golden-minimum-not-met")
  void promoteRefusesBelowTheGoldenMinimum() throws Exception {
    when(promotion.promote(eq(KIND), anyList(), any(), any()))
        .thenThrow(new LawReviewRefusal(Code.GOLDEN_MINIMUM_NOT_MET, "2 golden cases"));

    mvc.perform(as("admin-session", writes().get(3)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("golden-minimum-not-met"));
  }

  @Test
  void promoteAnswersThePullRequest() throws Exception {
    when(promotion.promote(KIND, List.of(ID), LocalDate.parse("1999-04-01"), null))
        .thenReturn(
            new PromotionResult(
                PR,
                "law-promote/qc-visit-notice/1999-04-01-r1-0000abcd",
                "document-validation/rulesets/qc-visit-notice/1999-04-01.json",
                1,
                List.of(ID)));

    mvc.perform(as("admin-session", writes().get(3)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prUrl").value(PR))
        .andExpect(jsonPath("$.revision").value(1));
    assertThat(AUDIT.getFirst().outcome()).isEqualTo("ok");
  }

  @Test
  void promotionNotConfiguredIs503AndAMissingDayIs400() throws Exception {
    when(promotion.goldenCases(KIND))
        .thenThrow(new LawReviewRefusal(Code.LAW_PROMOTION_OFF, "not configured"));

    mvc.perform(as("admin-session", get(BASE + "/kinds/" + KIND + "/golden")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("law-promotion-off"));
    mvc.perform(
            as(
                "admin-session",
                post(BASE + "/kinds/" + KIND + "/promote")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"proposalIds\":[\"" + ID + "\"]}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
    verify(promotion, never()).promote(anyString(), anyList(), any(), any());
  }

  @Test
  void theGoldenCasesAreCounted() throws Exception {
    when(promotion.goldenCases(KIND))
        .thenReturn(
            new GoldenCases(
                KIND,
                3,
                List.of(
                    new GoldenCase("fr-compliant"),
                    new GoldenCase("fr-defective"),
                    new GoldenCase("fr-blank"))));

    mvc.perform(as("admin-session", get(BASE + "/kinds/" + KIND + "/golden")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.met").value(true))
        .andExpect(jsonPath("$.documents").value(3))
        .andExpect(jsonPath("$.cases[2].form").value("BLANK"));
  }

  @Test
  void aDerivationWithoutItsWiringIs503() throws Exception {
    mvc.perform(as("admin-session", writes().get(4)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("law-derivation-off"));
  }

  @Test
  void theDemandIsListed() throws Exception {
    when(demand.list())
        .thenReturn(
            List.of(
                new KindDemand(
                    "QC",
                    "UNKNOWN",
                    12,
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-24T00:00:00Z"))));

    mvc.perform(as("admin-session", get(BASE + "/demand")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kindId").value("UNKNOWN"))
        .andExpect(jsonPath("$[0].count").value(12));
  }

  @Test
  void theManualImportNamesTheAdministratorAndRefusesWhileTheCorpusIsOff() throws Exception {
    mvc.perform(as("admin-session", writes().get(5)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("law-corpus-disabled"));
    verifyNoInteractions(imports);

    when(settings.corpusEnabled()).thenReturn(true);
    when(imports.importFile(any()))
        .thenReturn(new LawWatchReport(0, 3, List.of(), List.of(), List.of(UUID.randomUUID())));
    mvc.perform(as("admin-session", writes().get(5)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(3));
    verify(imports)
        .importFile(
            org.mockito.ArgumentMatchers.argThat(
                f ->
                    f.importedBy().equals(admin.toString())
                        && "QC".equals(f.jurisdiction())
                        && "https://www.donneesquebec.ca/ccq.xml".equals(f.sourceUrl())));
  }

  @Test
  void anArticleIsReadByPointInTimeAndImportsArePaged() throws Exception {
    when(catalogue.imports(anyInt())).thenReturn(List.of());

    mvc.perform(
            as(
                "admin-session",
                get(BASE + "/articles?jurisdiction=QC&instrument=CCQ-1991&ref=1931&language=fr")))
        .andExpect(status().isBadRequest());
    mvc.perform(as("admin-session", get(BASE + "/imports?limit=20"))).andExpect(status().isOk());
    verify(catalogue).imports(20);
  }
}
