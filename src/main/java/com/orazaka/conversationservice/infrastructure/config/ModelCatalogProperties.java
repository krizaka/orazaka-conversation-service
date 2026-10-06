package com.orazaka.conversationservice.infrastructure.config;

import com.orazaka.persistence.domain.model.CatalogModelDto;
import com.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Dynamic configuration class exposing the supported AI model catalog by media types. */
@Component
public class ModelCatalogProperties {

  private final CatalogModelManager catalogModelManager;

  public ModelCatalogProperties(CatalogModelManager catalogModelManager) {
    this.catalogModelManager =
        Objects.requireNonNull(catalogModelManager, "CatalogModelManager must not be null");
  }

  /**
   * Retrieves the dynamic catalog of supported models grouped by media types.
   *
   * @return A map containing lists of supported model names for 'speech', 'image', 'video', and
   *     'vision'.
   */
  public Map<String, List<String>> getModels() {
    Map<String, List<String>> catalog = new HashMap<>();

    catalog.put("speech", fetchModelNamesByCategory("speech"));
    catalog.put("image", fetchModelNamesByCategory("image"));
    catalog.put("video", fetchModelNamesByCategory("video"));
    catalog.put("vision", fetchModelNamesByCategory("vision"));
    catalog.put("theme", fetchModelNamesByCategory("theme"));
    catalog.put("audio", fetchModelNamesByCategory("audio"));
    catalog.put("code", fetchModelNamesByCategory("code"));

    return catalog;
  }

  private List<String> fetchModelNamesByCategory(String category) {
    return catalogModelManager.getModelsByCategory(category).stream()
        .map(CatalogModelDto::modelName)
        .toList();
  }
}
