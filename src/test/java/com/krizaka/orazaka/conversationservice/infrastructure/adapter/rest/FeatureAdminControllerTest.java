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

import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureRouteManager;
import com.krizaka.orazaka.persistence.domain.ports.inbound.FeatureToolBoundManager;
import com.krizaka.web.KrizakaWebProperties;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The admin endpoints of the per-feature tool bound and the feature routes (orazaka-ai-engine#169,
 * rule « admin configures everything »): read, replace, remove, a validated range, admin only, on
 * the audited operator surface.
 */
class FeatureAdminControllerTest {

  private FeatureToolBoundManager bounds;
  private FeatureRouteManager routes;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    bounds = mock(FeatureToolBoundManager.class);
    routes = mock(FeatureRouteManager.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new FeatureAdminController(bounds, routes))
            .setControllerAdvice(new ProblemDetailsAdvice(new KrizakaWebProperties(null, null)))
            .build();
  }

  @Test
  @DisplayName("GET lists the bounds with the range, the default row flagged")
  void listsBounds() throws Exception {
    Map<String, Integer> all = new LinkedHashMap<>();
    all.put("*", 8);
    all.put("agent.code.issue-to-pr", 3);
    when(bounds.all()).thenReturn(all);

    mvc.perform(get("/api/v1/models/admin/feature-tool-bounds"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.min").value(1))
        .andExpect(jsonPath("$.max").value(32))
        .andExpect(jsonPath("$.bounds[0].featureKey").value("*"))
        .andExpect(jsonPath("$.bounds[0].isDefault").value(true))
        .andExpect(jsonPath("$.bounds[1].maxToolRounds").value(3))
        .andExpect(jsonPath("$.bounds[1].isDefault").value(false));
  }

  @Test
  @DisplayName("PUT stores a bound and answers the stored row")
  void putStores() throws Exception {
    mvc.perform(
            put("/api/v1/models/admin/feature-tool-bounds/agent.code.issue-to-pr")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxToolRounds\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.featureKey").value("agent.code.issue-to-pr"))
        .andExpect(jsonPath("$.maxToolRounds").value(5));

    verify(bounds).upsert("agent.code.issue-to-pr", 5);
  }

  @Test
  @DisplayName("PUT outside the range is a 400 and nothing is stored")
  void putOutOfRange() throws Exception {
    doThrow(new IllegalArgumentException("maxToolRounds must be 1 to 32"))
        .when(bounds)
        .upsert(anyString(), anyInt());

    mvc.perform(
            put("/api/v1/models/admin/feature-tool-bounds/f")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxToolRounds\":99}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
  }

  @Test
  @DisplayName("PUT without maxToolRounds is a 400 before the store is touched")
  void putMissingField() throws Exception {
    mvc.perform(
            put("/api/v1/models/admin/feature-tool-bounds/f")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoMoreInteractions(bounds);
  }

  @Test
  @DisplayName("DELETE removes a feature's row (204), 404 when it had none, 400 for the default")
  void deleteRows() throws Exception {
    when(bounds.delete("a")).thenReturn(true);
    when(bounds.delete("b")).thenReturn(false);
    when(bounds.delete("*"))
        .thenThrow(new IllegalArgumentException("the default row cannot be removed"));

    mvc.perform(delete("/api/v1/models/admin/feature-tool-bounds/a"))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/models/admin/feature-tool-bounds/b"))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/models/admin/feature-tool-bounds/*"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET feature routes lists each feature with its models best first")
  void listsRoutes() throws Exception {
    Map<String, List<String>> all = new LinkedHashMap<>();
    all.put("agent.code.issue-to-pr", List.of("m1", "m2"));
    when(routes.allRoutes()).thenReturn(all);

    mvc.perform(get("/api/v1/models/admin/feature-routes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].featureKey").value("agent.code.issue-to-pr"))
        .andExpect(jsonPath("$[0].models[1]").value("m2"));
  }

  @Test
  @DisplayName("the controller is administrators only, and its writes are on the audited surface")
  void adminOnlyAndAudited() {
    PreAuthorize guard = FeatureAdminController.class.getAnnotation(PreAuthorize.class);

    assertThat(guard).isNotNull();
    assertThat(guard.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    assertThat(OperatorAuditPaths.audits("/api/v1/models/admin/feature-tool-bounds/{featureKey}"))
        .isTrue();
  }
}
