package com.krizaka.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One Testcontainers setup per JVM (ADR-034): a test class that depends on {@code
 * org.testcontainers} starts its own container, so each class pays a boot and the suite holds
 * several databases at once. The shared {@link AbstractContainerIntegrationTest} starts each
 * container once and hands its ports over through {@code @DynamicPropertySource}; a class that
 * needs a container extends it.
 */
class ContainerBaseClassTest {

  private static final JavaClasses TEST_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
          .importPackages("com.krizaka.orazaka.conversationservice");

  @Test
  @DisplayName("[ADR-034] a test that uses Testcontainers extends AbstractContainerIntegrationTest")
  void containerTestsShareTheSingletonBase() {
    noClasses()
        .that()
        .areNotAssignableTo(AbstractContainerIntegrationTest.class)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.testcontainers..")
        .because(
            "a container started by the test itself is a second database next to the shared one:"
                + " extend AbstractContainerIntegrationTest and read the ports from the"
                + " Environment")
        .check(TEST_CLASSES);
  }
}
