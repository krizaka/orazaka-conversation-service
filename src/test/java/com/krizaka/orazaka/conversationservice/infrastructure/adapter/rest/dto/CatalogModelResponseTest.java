package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.core.domain.model.HostCapability;
import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogModelResponseTest {

  private static final HostCapability APPLE_SILICON =
      new HostCapability(Set.of("APPLE_SILICON_MLX", "mps", "coreml"));

  private static CatalogModelDto model(String name, String category, String hardware) {
    return new CatalogModelDto(
        1, name, name, category, null, false, "localai-video", null, null, hardware, null);
  }

  @Test
  void cudaVideoModel_isIncompatibleOnAppleSilicon_withReason() {
    CatalogModelResponse view =
        CatalogModelResponse.from(
            model("stable-video-diffusion-img2vid-xt", "video", "cuda"), APPLE_SILICON);

    assertThat(view.compatible()).isFalse();
    assertThat(view.incompatibleReason()).contains("cuda");
  }

  @Test
  void mlxVideoModel_isCompatibleOnAppleSilicon() {
    CatalogModelResponse view =
        CatalogModelResponse.from(
            model("mlx-stable-diffusion-video", "video", "APPLE_SILICON_MLX"), APPLE_SILICON);

    assertThat(view.compatible()).isTrue();
    assertThat(view.incompatibleReason()).isNull();
  }

  @Test
  void imageToVideoModels_requireAReferenceImage() {
    assertThat(
            CatalogModelResponse.from(
                    model("stable-video-diffusion-img2vid-xt", "video", null), APPLE_SILICON)
                .requiresReferenceImage())
        .isTrue();
    assertThat(
            CatalogModelResponse.from(
                    model("mlx-stable-diffusion-video", "video", null), APPLE_SILICON)
                .requiresReferenceImage())
        .isTrue();
  }

  @Test
  void textToVideoAndImageModels_doNotRequireAReferenceImage() {
    assertThat(
            CatalogModelResponse.from(
                    model("animatediff-lightning-mps", "video", null), APPLE_SILICON)
                .requiresReferenceImage())
        .isFalse();
    assertThat(
            CatalogModelResponse.from(model("sd-1.5-apple-coreml", "image", null), APPLE_SILICON)
                .requiresReferenceImage())
        .isFalse();
  }
}
