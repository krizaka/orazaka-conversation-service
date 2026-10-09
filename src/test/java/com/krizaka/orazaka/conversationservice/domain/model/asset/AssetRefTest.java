package com.krizaka.orazaka.conversationservice.domain.model.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AssetRefTest {

  private static final Path ROOT = Path.of("/var/orazaka-uploads");

  @Test
  void resolvesUnderTheOwnersJobOutputDirectory() {
    AssetRef ref = new AssetRef("user-1", "job-7", "image.png");

    assertEquals(
        Path.of("/var/orazaka-uploads/user-1/job-7/output/image.png"), ref.resolveUnder(ROOT));
  }

  @Test
  @DisplayName("a traversal filename is rejected at construction, not at resolution")
  void rejects_aTraversalFilename() {
    // Refused by the type, so no caller can hold a reference that would need re-checking.
    assertThrows(
        IllegalArgumentException.class, () -> new AssetRef("user-1", "job-7", "../../etc/passwd"));
    assertThrows(
        IllegalArgumentException.class, () -> new AssetRef("user-1", "job-7", "sub/image.png"));
    assertThrows(
        IllegalArgumentException.class, () -> new AssetRef("user-1", "job-7", "sub\\image.png"));
  }

  @Test
  @DisplayName("a traversal owner or job id cannot escape the root either")
  void rejects_anEscapingOwnerOrJob() {
    // These come from storage rather than the request, but the guarantee must not depend on
    // every future writer of those columns behaving.
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssetRef("../../..", "job-7", "image.png").resolveUnder(ROOT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssetRef("user-1", "../../..", "image.png").resolveUnder(ROOT));
  }

  @Test
  void rejects_aMissingOrBlankComponent() {
    assertThrows(NullPointerException.class, () -> new AssetRef(null, "job-7", "image.png"));
    assertThrows(IllegalArgumentException.class, () -> new AssetRef(" ", "job-7", "image.png"));
    assertThrows(NullPointerException.class, () -> new AssetRef("user-1", null, "image.png"));
    assertThrows(IllegalArgumentException.class, () -> new AssetRef("user-1", " ", "image.png"));
    assertThrows(NullPointerException.class, () -> new AssetRef("user-1", "job-7", null));
    assertThrows(IllegalArgumentException.class, () -> new AssetRef("user-1", "job-7", " "));
  }

  @Test
  void normalisesARelativeRootBeforeComparing() {
    AssetRef ref = new AssetRef("user-1", "job-7", "image.png");

    Path resolved = ref.resolveUnder(Path.of("var/./orazaka-uploads"));

    assertTrue(resolved.isAbsolute(), "a resolved asset path is absolute");
    assertTrue(resolved.endsWith(Path.of("user-1/job-7/output/image.png")));
  }
}
