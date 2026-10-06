package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.orazaka.conversationservice.infrastructure.adapter.rest.dto.CatalogModelResponse;
import com.orazaka.conversationservice.infrastructure.config.ModelCatalogProperties;
import com.orazaka.core.domain.model.OllamaCatalog;
import com.orazaka.core.domain.ports.outbound.ModelCatalogProvider;
import com.orazaka.core.infrastructure.host.HostCapabilityDetector;
import com.orazaka.persistence.domain.model.CatalogModelDto;
import com.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the {@code models} resource: discovery/catalog reads plus admin CRUD.
 *
 * <p>Consolidates the former Model and AdminModel controllers. Mutating endpoints are guarded with
 * method-level {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

  private final ModelCatalogProvider modelCatalogProvider;
  private final ModelCatalogProperties modelCatalogProperties;
  private final CatalogModelManager catalogModelManager;
  private final HostCapabilityDetector hostCapabilityDetector;

  public ModelController(
      ModelCatalogProvider modelCatalogProvider,
      ModelCatalogProperties modelCatalogProperties,
      CatalogModelManager catalogModelManager,
      HostCapabilityDetector hostCapabilityDetector) {
    this.modelCatalogProvider =
        Objects.requireNonNull(modelCatalogProvider, "ModelCatalogProvider must not be null");
    this.modelCatalogProperties =
        Objects.requireNonNull(modelCatalogProperties, "ModelCatalogProperties must not be null");
    this.catalogModelManager =
        Objects.requireNonNull(catalogModelManager, "CatalogModelManager must not be null");
    this.hostCapabilityDetector =
        Objects.requireNonNull(hostCapabilityDetector, "HostCapabilityDetector must not be null");
  }

  /** Retrieves the local catalog of Ollama models. */
  @GetMapping
  public ResponseEntity<@NotNull OllamaCatalog> getModels() {
    return modelCatalogProvider
        .getCatalog()
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Retrieves the list of supported model names grouped by media type. */
  @GetMapping("/supported")
  public ResponseEntity<@NotNull Map<String, List<String>>> getSupportedModels() {
    return ResponseEntity.ok(
        modelCatalogProperties.getModels() != null ? modelCatalogProperties.getModels() : Map.of());
  }

  /** Retrieves the complete AI model catalog definitions from the database. */
  @GetMapping("/catalog")
  public ResponseEntity<@NotNull List<CatalogModelResponse>> getCatalogModels() {
    var host = hostCapabilityDetector.capability();
    List<CatalogModelResponse> models =
        catalogModelManager.getAllModels().stream()
            .map(dto -> CatalogModelResponse.from(dto, host))
            .toList();
    return ResponseEntity.ok(models);
  }

  /** Retrieves all available AI provider names from the database. */
  @GetMapping("/providers")
  public ResponseEntity<@NotNull List<String>> getProviders() {
    return ResponseEntity.ok(catalogModelManager.getAllProviders());
  }

  /** Adds a new model definition to the catalog. */
  @PostMapping
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull CatalogModelDto> createModel(@RequestBody CatalogModelDto dto) {
    return ResponseEntity.ok(catalogModelManager.saveModel(dto));
  }

  /** Updates an existing model definition in the catalog. */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull CatalogModelDto> updateModel(
      @PathVariable Integer id, @RequestBody CatalogModelDto dto) {
    CatalogModelDto toUpdate =
        new CatalogModelDto(
            id,
            dto.modelName(),
            dto.modelLabel(),
            dto.category(),
            dto.options(),
            dto.isDefault(),
            dto.providerName());
    return ResponseEntity.ok(catalogModelManager.saveModel(toUpdate));
  }

  /** Deletes a model definition by its database ID. */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull Void> deleteModel(@PathVariable Integer id) {
    catalogModelManager.deleteModel(id);
    return ResponseEntity.ok().build();
  }
}
