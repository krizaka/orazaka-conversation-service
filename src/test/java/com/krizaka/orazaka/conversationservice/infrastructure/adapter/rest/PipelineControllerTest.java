package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.core.application.pipeline.DynamicPipelineExecutor;
import com.krizaka.orazaka.core.domain.model.ValidationPipelineConfiguration;
import com.krizaka.orazaka.core.domain.model.ValidationStepType;
import com.krizaka.orazaka.core.domain.ports.outbound.PipelineConfigProvider;
import com.krizaka.orazaka.core.domain.ports.outbound.ValidationPipelineRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link PipelineController} (interceptor chain + validation matrix). */
class PipelineControllerTest {

  private final PipelineConfigProvider configProvider = mock(PipelineConfigProvider.class);
  private final DynamicPipelineExecutor pipeline = mock(DynamicPipelineExecutor.class);
  private final ValidationPipelineRepository repository = mock(ValidationPipelineRepository.class);
  private final PipelineController controller =
      new PipelineController(configProvider, pipeline, repository);

  // ── Interceptor chain ─────────────────────────────────────────────────────

  @Test
  @DisplayName("GET /interceptors returns the current config")
  void getInterceptors_returnsCurrentConfig() {
    when(pipeline.getCurrentConfig()).thenReturn(List.of());
    var response = controller.getInterceptors();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEmpty();
  }

  @Test
  @DisplayName("POST /interceptors/reset restores defaults and evicts the cache")
  void resetToDefaults_evictsCache() {
    when(pipeline.buildDefaultConfigs()).thenReturn(List.of());
    var response = controller.resetToDefaults();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(configProvider).resetToDefaults(List.of());
    verify(pipeline).evictChainCache();
  }

  // ── Validation matrix ─────────────────────────────────────────────────────

  @Test
  @DisplayName("GET /validation returns ordered validation pipeline configs")
  void getValidationPipeline_returnsOrderedConfigs() {
    var configA =
        new ValidationPipelineConfiguration(
            UUID.randomUUID(), ValidationStepType.STRUCTURAL_A, true, 1, Map.of());
    var configD =
        new ValidationPipelineConfiguration(
            UUID.randomUUID(), ValidationStepType.TDR_D, false, 4, Map.of());
    when(repository.findAllOrderedByExecution()).thenReturn(List.of(configA, configD));

    ResponseEntity<List<ValidationPipelineConfiguration>> response =
        controller.getValidationPipeline();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).hasSize(2);
    assertThat(response.getBody().get(0).stepType()).isEqualTo(ValidationStepType.STRUCTURAL_A);
    assertThat(response.getBody().get(1).stepType()).isEqualTo(ValidationStepType.TDR_D);
  }

  @Test
  @DisplayName("GET /validation returns empty list when no configs exist")
  void getValidationPipeline_emptyList() {
    when(repository.findAllOrderedByExecution()).thenReturn(List.of());
    ResponseEntity<List<ValidationPipelineConfiguration>> response =
        controller.getValidationPipeline();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEmpty();
  }

  @Test
  @DisplayName("PUT /validation saves configs and returns saved results")
  void updateValidationPipeline_savesAndReturns() {
    var inputA =
        new ValidationPipelineConfiguration(
            UUID.randomUUID(), ValidationStepType.STRUCTURAL_A, false, 2, Map.of());
    var inputD =
        new ValidationPipelineConfiguration(
            UUID.randomUUID(), ValidationStepType.TDR_D, true, 1, Map.of("model", "qwen"));
    when(repository.saveAll(List.of(inputA, inputD))).thenReturn(List.of(inputA, inputD));

    ResponseEntity<List<ValidationPipelineConfiguration>> response =
        controller.updateValidationPipeline(List.of(inputA, inputD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).hasSize(2);
    verify(repository).saveAll(List.of(inputA, inputD));
  }

  @Test
  @DisplayName("PUT /validation with empty list saves nothing")
  void updateValidationPipeline_emptyInput() {
    when(repository.saveAll(List.of())).thenReturn(List.of());
    ResponseEntity<List<ValidationPipelineConfiguration>> response =
        controller.updateValidationPipeline(List.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEmpty();
  }

  // ── Constructor guard ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Constructor rejects null dependencies")
  void constructor_nullRepository_throws() {
    assertThatThrownBy(() -> new PipelineController(configProvider, pipeline, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ValidationPipelineRepository");
  }
}
