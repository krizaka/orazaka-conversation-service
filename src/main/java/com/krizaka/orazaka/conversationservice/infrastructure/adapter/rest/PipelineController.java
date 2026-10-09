package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.core.application.pipeline.DynamicPipelineExecutor;
import com.krizaka.orazaka.core.domain.model.InterceptorConfig;
import com.krizaka.orazaka.core.domain.model.ValidationPipelineConfiguration;
import com.krizaka.orazaka.core.domain.ports.outbound.PipelineConfigProvider;
import com.krizaka.orazaka.core.domain.ports.outbound.ValidationPipelineRepository;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only REST controller for the {@code pipeline} resource: the interceptor execution chain and
 * the validation-tier matrix.
 *
 * <p>Consolidates the former AdminPipeline and ValidationAdmin controllers. After every interceptor
 * mutation the pipeline chain cache is evicted to force an immediate rebuild.
 */
@RestController
@RequestMapping("/api/v1/pipeline")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class PipelineController {

  private static final Logger logger = LoggerFactory.getLogger(PipelineController.class);

  private final PipelineConfigProvider configProvider;
  private final DynamicPipelineExecutor pipeline;
  private final ValidationPipelineRepository validationPipelineRepository;

  public PipelineController(
      PipelineConfigProvider configProvider,
      DynamicPipelineExecutor pipeline,
      ValidationPipelineRepository validationPipelineRepository) {
    this.configProvider =
        Objects.requireNonNull(configProvider, "PipelineConfigProvider must not be null");
    this.pipeline = Objects.requireNonNull(pipeline, "DynamicPipelineExecutor must not be null");
    this.validationPipelineRepository =
        Objects.requireNonNull(
            validationPipelineRepository, "ValidationPipelineRepository must not be null");
  }

  // ── Interceptor chain ─────────────────────────────────────────────────────

  /** Retrieves all pipeline interceptor configurations ordered by execution order. */
  @GetMapping("/interceptors")
  public ResponseEntity<@NotNull List<InterceptorConfig>> getInterceptors() {
    return ResponseEntity.ok(pipeline.getCurrentConfig());
  }

  /** Bulk-updates pipeline interceptor ordering and enabled state. */
  @PutMapping("/interceptors")
  public ResponseEntity<@NotNull List<InterceptorConfig>> updateInterceptors(
      @RequestBody List<InterceptorConfig> configs) {
    logger.info("Admin pipeline update: saving {} interceptor configs.", configs.size());
    List<InterceptorConfig> saved = configProvider.saveAll(configs);
    pipeline.evictChainCache();
    return ResponseEntity.ok(saved);
  }

  /** Resets all pipeline interceptor configurations to hardcoded defaults. */
  @PostMapping("/interceptors/reset")
  public ResponseEntity<@NotNull List<InterceptorConfig>> resetToDefaults() {
    logger.info("Admin pipeline reset: restoring hardcoded defaults.");
    List<InterceptorConfig> defaults = pipeline.buildDefaultConfigs();
    configProvider.resetToDefaults(defaults);
    pipeline.evictChainCache();
    return ResponseEntity.ok(defaults);
  }

  // ── Validation-tier matrix ────────────────────────────────────────────────

  /** Retrieves all validation pipeline tier configurations ordered by execution order. */
  @GetMapping("/validation")
  public ResponseEntity<@NotNull List<ValidationPipelineConfiguration>> getValidationPipeline() {
    return ResponseEntity.ok(validationPipelineRepository.findAllOrderedByExecution());
  }

  /** Bulk-updates validation pipeline tier ordering and enabled state. */
  @PutMapping("/validation")
  public ResponseEntity<@NotNull List<ValidationPipelineConfiguration>> updateValidationPipeline(
      @RequestBody List<ValidationPipelineConfiguration> configs) {
    logger.info("Admin validation pipeline update: saving {} tier configs.", configs.size());
    List<ValidationPipelineConfiguration> saved = validationPipelineRepository.saveAll(configs);
    return ResponseEntity.ok(saved);
  }
}
