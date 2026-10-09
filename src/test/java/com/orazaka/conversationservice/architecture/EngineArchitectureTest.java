package com.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces engine isolation, Spring AI model injection boundaries, and router routing architecture.
 */
class EngineArchitectureTest {

  private static JavaClasses allAppClasses;

  @BeforeAll
  static void importClasses() {
    allAppClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.orazaka");
  }

  @Test
  @DisplayName(
      "Classes in com.orazaka.core.. do not define custom maps or registries for resolving AI models")
  void executionDoesNotDependOnRegistryOrOverrides() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.core..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("EngineModelRegistry")
        .orShould()
        .haveSimpleNameEndingWith("ModelRegistry")
        .because("EngineModelRegistry is deleted and orchestration should rely on native DI")
        .check(allAppClasses);
  }

  @Test
  @DisplayName("Custom security filters do not re-implement token decoding or authority checks")
  void customSecurityFiltersDoNotReimplementTokenDecodingOrAuthorityChecks() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice..")
        .and()
        .haveSimpleNameEndingWith("Filter")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.krizaka.users.service.IdentityService")
        .because(
            "Custom security filters must not bypass official Spring Security token decoding or authority checks")
        .check(allAppClasses);
  }

  @Test
  @DisplayName("Engine is final and communicates only through Spring AI model interfaces")
  void engineCommunicatesOnlyThroughSpringAiInterfaces() {
    // Assert Engine class is final
    classes()
        .that()
        .haveFullyQualifiedName("com.orazaka.core.application.engine.Engine")
        .should()
        .haveModifier(com.tngtech.archunit.core.domain.JavaModifier.FINAL)
        .because("Engine must be final")
        .check(allAppClasses);

    // Assert Engine/AbstractEngine does not use concrete model implementations directly
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.core.application.engine..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("OpenAiChatModel")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("OllamaChatModel")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("OpenAiImageModel")
        .because("Engine must only communicate through official Spring AI model interfaces")
        .check(allAppClasses);
  }

  @Test
  @DisplayName(
      "All controllers reside in an infrastructure.adapter.. ingress and never bypass the DynamicPipelineExecutor layer")
  void controllersResideInEndpointAndDoNotBypassPipeline() {
    // Strangler-fig Phase 2: each bounded context owns its segregated REST ingress
    // (identity hosts auth/profile/api-keys/credentials); the invariant is that a
    // controller lives in ITS module's infrastructure.adapter.. package [ERR-112].
    classes()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .resideInAPackage("..infrastructure.adapter..")
        .because("All controllers must reside in their module's infrastructure.adapter.. package")
        .check(allAppClasses);

    // Assert that controllers never bypass DynamicPipelineExecutor/AiClient/GraphEngine to call
    // models directly
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice.infrastructure.adapter..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("Engine")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("AbstractEngine")
        .because(
            "Controllers must not bypass the DynamicPipelineExecutor/AiClient layer to interact with the engine directly")
        .check(allAppClasses);
  }

  @Test
  @DisplayName("Classes in com.orazaka.core.. do not depend on com.krizaka.users.repository..")
  void coreDoesNotDependOnIdentityRepository() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.core..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.krizaka.users.repository..")
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("com.krizaka.users.infrastructure.persistence.repository..")
        .because(
            "Core must interact solely through high-level public Identity interface contracts, never repository/persistence layers")
        .check(allAppClasses);
  }
}
