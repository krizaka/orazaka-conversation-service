package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureCachePolicyManager;
import com.krizaka.web.KrizakaWebProperties;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The admin endpoints of the per-feature prompt-cache policy (orazaka-ai-engine#173, rule « admin
 * configures everything »): read, replace, remove, a closed set of strategies, admin only, on the
 * audited operator surface.
 */
class FeatureCachePolicyAdminControllerTest {

  private static final String BASE = "/api/v1/models/admin/feature-cache-policies";

  private FeatureCachePolicyManager policies;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    policies = mock(FeatureCachePolicyManager.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new FeatureCachePolicyAdminController(policies))
            .setControllerAdvice(new ProblemDetailsAdvice(new KrizakaWebProperties(null, null)))
            .build();
  }

  @Test
  @DisplayName("GET lists the policies with the strategies on offer, the default row flagged")
  void listsPolicies() throws Exception {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("*", "SYSTEM_ONLY");
    all.put("agent.code.issue-to-pr", "SYSTEM_AND_TOOLS");
    when(policies.all()).thenReturn(all);

    mvc.perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategies.length()").value(3))
        .andExpect(jsonPath("$.policies[0].featureKey").value("*"))
        .andExpect(jsonPath("$.policies[0].isDefault").value(true))
        .andExpect(jsonPath("$.policies[1].strategy").value("SYSTEM_AND_TOOLS"))
        .andExpect(jsonPath("$.policies[1].isDefault").value(false));
  }

  @Test
  @DisplayName("PUT stores a policy and answers the stored row")
  void putStores() throws Exception {
    mvc.perform(
            put(BASE + "/agent.code.issue-to-pr")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"NONE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.featureKey").value("agent.code.issue-to-pr"))
        .andExpect(jsonPath("$.strategy").value("NONE"));

    verify(policies).upsert("agent.code.issue-to-pr", "NONE");
  }

  @Test
  @DisplayName("PUT with an unknown strategy is a 400")
  void putUnknownStrategy() throws Exception {
    doThrow(new IllegalArgumentException("strategy must be one of NONE, SYSTEM_ONLY"))
        .when(policies)
        .upsert(anyString(), anyString());

    mvc.perform(
            put(BASE + "/f")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"EVERYTHING\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
  }

  @Test
  @DisplayName("PUT without a strategy is a 400 before the store is touched")
  void putMissingField() throws Exception {
    mvc.perform(put(BASE + "/f").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoMoreInteractions(policies);
  }

  @Test
  @DisplayName("DELETE removes a feature's row (204), 404 when it had none, 400 for the default")
  void deleteRows() throws Exception {
    when(policies.remove("a")).thenReturn(true);
    when(policies.remove("b")).thenReturn(false);
    when(policies.remove("*")).thenThrow(new IllegalArgumentException("the default row stays"));

    mvc.perform(delete(BASE + "/a")).andExpect(status().isNoContent());
    mvc.perform(delete(BASE + "/b")).andExpect(status().isNotFound());
    mvc.perform(delete(BASE + "/*")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the controller is administrators only, and its writes are on the audited surface")
  void adminOnlyAndAudited() {
    PreAuthorize guard = FeatureCachePolicyAdminController.class.getAnnotation(PreAuthorize.class);

    assertThat(guard).isNotNull();
    assertThat(guard.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    assertThat(OperatorAuditPaths.audits(BASE + "/{featureKey}")).isTrue();
  }
}
