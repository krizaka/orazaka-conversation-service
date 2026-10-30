package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureStructuredBoundManager;
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
 * The admin endpoints of the per-feature structured-output bound (orazaka-ai-engine#172, rule «
 * admin configures everything »): read, replace, remove, a validated range, admin only, on the
 * audited operator surface.
 */
class FeatureStructuredBoundAdminControllerTest {

  private static final String BASE = "/api/v1/models/admin/feature-structured-bounds";

  private FeatureStructuredBoundManager bounds;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    bounds = mock(FeatureStructuredBoundManager.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new FeatureStructuredBoundAdminController(bounds))
            .setControllerAdvice(new ProblemDetailsAdvice(new KrizakaWebProperties(null, null)))
            .build();
  }

  @Test
  @DisplayName("GET lists the bounds with the range, the default row flagged")
  void listsBounds() throws Exception {
    Map<String, Integer> all = new LinkedHashMap<>();
    all.put("*", 2);
    all.put("agent.code.issue-to-pr", 4);
    when(bounds.all()).thenReturn(all);

    mvc.perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.min").value(1))
        .andExpect(jsonPath("$.max").value(5))
        .andExpect(jsonPath("$.bounds[0].featureKey").value("*"))
        .andExpect(jsonPath("$.bounds[0].isDefault").value(true))
        .andExpect(jsonPath("$.bounds[1].maxAttempts").value(4))
        .andExpect(jsonPath("$.bounds[1].isDefault").value(false));
  }

  @Test
  @DisplayName("PUT stores a bound and answers the stored row")
  void putStores() throws Exception {
    mvc.perform(
            put(BASE + "/agent.code.issue-to-pr")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxAttempts\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.featureKey").value("agent.code.issue-to-pr"))
        .andExpect(jsonPath("$.maxAttempts").value(3));

    verify(bounds).upsert("agent.code.issue-to-pr", 3);
  }

  @Test
  @DisplayName("PUT outside the range is a 400")
  void putOutOfRange() throws Exception {
    doThrow(new IllegalArgumentException("maxAttempts must be 1 to 5"))
        .when(bounds)
        .upsert(anyString(), anyInt());

    mvc.perform(
            put(BASE + "/f").contentType(MediaType.APPLICATION_JSON).content("{\"maxAttempts\":9}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
  }

  @Test
  @DisplayName("PUT without maxAttempts is a 400 before the store is touched")
  void putMissingField() throws Exception {
    mvc.perform(put(BASE + "/f").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoMoreInteractions(bounds);
  }

  @Test
  @DisplayName("DELETE removes a feature's row (204), 404 when it had none, 400 for the default")
  void deleteRows() throws Exception {
    when(bounds.delete("a")).thenReturn(true);
    when(bounds.delete("b")).thenReturn(false);
    when(bounds.delete("*")).thenThrow(new IllegalArgumentException("the default row stays"));

    mvc.perform(delete(BASE + "/a")).andExpect(status().isNoContent());
    mvc.perform(delete(BASE + "/b")).andExpect(status().isNotFound());
    mvc.perform(delete(BASE + "/*")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the controller is administrators only, and its writes are on the audited surface")
  void adminOnlyAndAudited() {
    PreAuthorize guard =
        FeatureStructuredBoundAdminController.class.getAnnotation(PreAuthorize.class);

    assertThat(guard).isNotNull();
    assertThat(guard.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    assertThat(OperatorAuditPaths.audits(BASE + "/{featureKey}")).isTrue();
  }
}
