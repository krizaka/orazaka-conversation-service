package com.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Router-specific architectural boundary tests. Enforces that router infrastructure classes
 * maintain proper encapsulation and that Spring AI types do not leak into the router layer.
 */
class RouterBoundaryTest {

  private static JavaClasses routerClasses;

  @BeforeAll
  static void importClasses() {
    routerClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.orazaka.conversationservice");
  }

  @Test
  @DisplayName("[ERR-110] Config infrastructure classes must not be public")
  void configClassesMustBePackagePrivate() {
    classes()
        .that()
        .resideInAPackage("com.orazaka.conversationservice.infrastructure.config..")
        .and()
        .areNotInterfaces()
        .and()
        .areNotAnnotatedWith(Configuration.class)
        .and()
        .haveSimpleNameNotEndingWith("Properties")
        .and()
        .areNotMemberClasses()
        .should()
        .notBePublic()
        .because(
            "Infrastructure filters and request wrappers must be package-private [ERR-110, ADR-009]."
                + " @Configuration, *Properties, and nested member classes are exempt"
                + " (Spring Binder requirement).")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[Section 1.A] Router must not depend on Spring AI framework types")
  void routerMustNotDependOnSpringAi() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.ai..")
        .because(
            "Spring AI types must remain encapsulated inside orazaka-core."
                + " Router interacts only through AiClient facade [Section 1.A, ADR-005]")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[Gate-DI] Router classes must not use @Autowired on fields")
  void noFieldInjection() {
    noFields()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("com.orazaka.conversationservice..")
        .should()
        .beAnnotatedWith(Autowired.class)
        .because(
            "Field injection is prohibited. Constructor-based DI is mandatory [ADR-012, AGENTS.md]")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[ERR-106] Router DTO package must not depend on identity domain types")
  void routerDtoIsDomainBlind() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice.domain.model..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.orazaka.identity.domain..")
        .because(
            "DTO layer must be domain-blind — domain-to-DTO mapping happens at factory boundary [ERR-106]")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[ERR-102] Router endpoints must not import identity entities")
  void routerEndpointsDoNotLeakEntities() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice.infrastructure.adapter..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.orazaka.identity.infrastructure.persistence.entity..")
        .because(
            "Endpoints must interact with identity only through service interfaces and DTOs [ERR-102]")
        .check(routerClasses);
  }
}
