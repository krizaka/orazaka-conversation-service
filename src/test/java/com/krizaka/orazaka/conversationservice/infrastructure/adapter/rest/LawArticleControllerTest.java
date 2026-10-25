package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.krizaka.orazaka.conversationservice.infrastructure.config.SecurityConfig;
import com.krizaka.orazaka.tools.application.service.LawArticleReadService;
import com.krizaka.orazaka.tools.domain.model.law.LawArticleQuery;
import com.krizaka.orazaka.tools.domain.model.law.LawArticleRead;
import com.krizaka.orazaka.tools.domain.model.law.LawArticleRead.Outcome;
import com.krizaka.orazaka.tools.domain.model.law.QuoteCheck;
import com.krizaka.orazaka.tools.domain.model.law.StatuteVersion;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import com.krizaka.web.KrizakaWebProperties;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code GET /api/v1/law/articles} (issue #89) behind THIS service's real security chain: a signed
 * in caller (the edge exchanges an {@code oz_} key for that session) reads an article; every
 * outcome of the use case has its status; nothing reaches the use case without a session.
 */
@SpringJUnitWebConfig(LawArticleControllerTest.Wiring.class)
class LawArticleControllerTest {

  private static final String PATH = "/api/v1/law/articles";
  private static final String HASH = "a".repeat(64);
  private static final UUID IMPORT = UUID.fromString("0b1e5f3e-7c55-4b4c-9a65-0f8f6a3c2d10");

  /** The collaborators of {@link SecurityConfig} and the use case, stubbed. */
  @Configuration
  @EnableWebMvc
  @Import({SecurityConfig.class, LawArticleController.class})
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
    LawArticleReadService lawArticleReadService() {
      return mock(LawArticleReadService.class);
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
  @Autowired LawArticleReadService reads;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  Filter securityChain;

  MockMvc mvc;
  UUID caller;

  @BeforeEach
  void setUp() {
    reset(reads);
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
    caller = UUID.randomUUID();
    when(jwt.decode("session"))
        .thenReturn(
            Jwt.withTokenValue("session")
                .header("alg", "HS256")
                .subject(caller.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
    when(users.getUser(caller.toString()))
        .thenReturn(new User(caller, "u", "u@example.com", true, Set.of("ROLE_USER"), Map.of()));
  }

  private static StatuteVersion version() {
    return new StatuteVersion(
        "Le locateur doit donner un avis de 24 heures.",
        HASH,
        LocalDate.of(1994, 1, 1),
        "https://www.legisquebec.gouv.qc.ca/fr/version/lc/CCQ-1991?code=se:1931",
        IMPORT);
  }

  private void answers(LawArticleRead read) {
    when(reads.read(any(LawArticleQuery.class), anyString())).thenReturn(read);
  }

  private org.springframework.test.web.servlet.ResultActions call(String... params)
      throws Exception {
    var request = get(PATH).header("Authorization", "Bearer session");
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mvc.perform(request);
  }

  private org.springframework.test.web.servlet.ResultActions callArticle1931(String... extra)
      throws Exception {
    String[] base = {
      "jurisdiction",
      "QC",
      "instrument",
      "CCQ-1991",
      "ref",
      "1931",
      "language",
      "fr",
      "asOf",
      "2026-10-20"
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return call(all);
  }

  @Test
  @DisplayName("no session, no read: 401 and the use case is never called")
  void anonymousIsRefused() throws Exception {
    mvc.perform(get(PATH).param("asOf", "2026-10-20")).andExpect(status().isUnauthorized());
    verify(reads, never()).read(any(), anyString());
  }

  @Test
  @DisplayName("found: 200 with the text, its hash, the day, the citation and the import")
  void foundIsTheArticle() throws Exception {
    answers(new LawArticleRead(Outcome.FOUND, "read-1", version(), null));

    callArticle1931()
        .andExpect(status().isOk())
        .andExpect(header().string("X-Orazaka-Read-Id", "read-1"))
        .andExpect(jsonPath("$.text").value("Le locateur doit donner un avis de 24 heures."))
        .andExpect(jsonPath("$.contentHash").value(HASH))
        .andExpect(jsonPath("$.inForceFrom").value("1994-01-01"))
        .andExpect(
            jsonPath("$.officialUrl")
                .value("https://www.legisquebec.gouv.qc.ca/fr/version/lc/CCQ-1991?code=se:1931"))
        .andExpect(jsonPath("$.importId").value(IMPORT.toString()))
        .andExpect(jsonPath("$.quote").value(nullValue()));
    verify(reads)
        .read(
            eq(new LawArticleQuery("QC", "CCQ-1991", "1931", "fr", "2026-10-20", null)),
            eq(caller.toString()));
  }

  @Test
  @DisplayName("the quote check is in the answer: VERIFIED or NOT_IN_ARTICLE")
  void theQuoteCheckIsReturned() throws Exception {
    answers(new LawArticleRead(Outcome.FOUND, "read-2", version(), QuoteCheck.NOT_IN_ARTICLE));

    callArticle1931("quote", "avis de 48 heures")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quote").value("NOT_IN_ARTICLE"));
    verify(reads)
        .read(
            eq(
                new LawArticleQuery(
                    "QC", "CCQ-1991", "1931", "fr", "2026-10-20", "avis de 48 heures")),
            anyString());
  }

  @Test
  @DisplayName("a missing or unreadable asOf (any invalid question) is a 400 invalid-request")
  void anInvalidQuestionIs400() throws Exception {
    when(reads.read(any(LawArticleQuery.class), anyString()))
        .thenThrow(new IllegalArgumentException("asOf is required, as an ISO day (yyyy-MM-dd)"));

    call("jurisdiction", "QC", "instrument", "CCQ-1991", "ref", "1931", "language", "fr")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"))
        .andExpect(jsonPath("$.detail").value("asOf is required, as an ISO day (yyyy-MM-dd)"));
  }

  @Test
  @DisplayName("LAW_VERSION_UNKNOWN is a 404, with no text")
  void unknownIs404() throws Exception {
    answers(LawArticleRead.without(Outcome.LAW_VERSION_UNKNOWN, "read-3"));

    callArticle1931()
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("law-version-unknown"))
        .andExpect(jsonPath("$.outcome").value("LAW_VERSION_UNKNOWN"))
        .andExpect(jsonPath("$.text").doesNotExist());
  }

  @Test
  @DisplayName("LAW_CORPUS_DISABLED is a 503: the operator's switch, not the caller's question")
  void disabledIs503() throws Exception {
    answers(LawArticleRead.without(Outcome.LAW_CORPUS_DISABLED, null));

    callArticle1931()
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("law-corpus-disabled"))
        .andExpect(jsonPath("$.outcome").value("LAW_CORPUS_DISABLED"));
  }

  @Test
  @DisplayName("LAW_INTEGRITY_ERROR is a 500 with no text: the stored text failed its hash")
  void integrityErrorIs500WithoutText() throws Exception {
    answers(LawArticleRead.without(Outcome.LAW_INTEGRITY_ERROR, "read-4"));

    callArticle1931()
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("law-integrity-error"))
        .andExpect(jsonPath("$.outcome").value("LAW_INTEGRITY_ERROR"))
        .andExpect(jsonPath("$.text").doesNotExist());
  }

  @Test
  @DisplayName("a budget that cannot hold the call is a 402; no meter wired is a 503")
  void meteringRefusals() throws Exception {
    answers(LawArticleRead.without(Outcome.LAW_BUDGET_EXCEEDED, null));
    callArticle1931()
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value("law-budget-exceeded"));

    answers(LawArticleRead.without(Outcome.LAW_METERING_UNAVAILABLE, null));
    callArticle1931()
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("law-metering-unavailable"));
  }
}
