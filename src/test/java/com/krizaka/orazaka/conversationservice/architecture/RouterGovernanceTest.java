package com.krizaka.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.orazaka.test.architecture.ConfigBindingRules;
import com.krizaka.orazaka.test.architecture.GovernanceRules;
import com.krizaka.orazaka.test.architecture.LoggedContentRules;
import com.krizaka.orazaka.test.architecture.PackPurityRules;
import com.krizaka.orazaka.test.architecture.RunSurfaceRules;
import com.krizaka.orazaka.test.architecture.SourceFileScanner;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Governance guardrails for the orazaka-router module.
 *
 * <p>Mirrors core governance rules: anonymous class ban (with framework exemptions) and localhost
 * source scanning.
 */
class RouterGovernanceTest {

  /**
   * The rules below are repository-wide, not module-scoped: the worst pack coupling lives in the
   * Python media worker, which is in no Maven reactor, so a per-module scan could never see it.
   */
  private static final Path REPOSITORY_ROOT =
      PackPurityRules.locateRepositoryRoot(Path.of(System.getProperty("user.dir")));

  /** Framework interfaces that legitimately require anonymous implementations. */
  private static final Set<String> EXEMPT_SUPERTYPES =
      Set.of("jakarta.servlet.ServletInputStream", "graphql.schema.Coercing");

  private static JavaClasses routerClasses;

  @BeforeAll
  static void importClasses() {
    routerClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.krizaka.orazaka.conversationservice");
  }

  @Test
  @DisplayName("[DOOR-001] no inbound HTTP entry dispatches a job")
  void noInboundEntryDispatchesAJob() {
    RunSurfaceRules.assertNoInboundEntryDispatchesAJob();
  }

  @Test
  @DisplayName("[GOV-001] No anonymous classes in router (framework exempt)")
  void noAnonymousClassesInProduction() {
    classes()
        .that()
        .resideInAPackage("com.krizaka.orazaka.conversationservice..")
        .should()
        .notBeAnonymousClasses()
        .orShould(beFrameworkAnonymousClass())
        .because(
            "Anonymous classes are banned in production — use explicit named implementations"
                + " or lambda expressions. Framework-required patterns are exempt.")
        .check(routerClasses);
  }

  @Test
  @DisplayName(
      "[GOV-004] No classes in router production should access standard streams (System.out/System.err)")
  void noStandardStreams() {
    com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
        .because("Use SLF4J loggers instead of standard output/error streams")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[GOV-003] No hardcoded localhost or dummy keys in router production source")
  void noHardcodedLocalhostInProductionSource() throws IOException {
    Path sourceRoot = Path.of("orazaka-conversation-service/src/main/java");
    if (!Files.exists(sourceRoot)) {
      sourceRoot = Path.of("src/main/java");
    }

    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      paths
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  List<String> lines = Files.readAllLines(path);
                  for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                      continue;
                    }
                    if (line.contains("localhost:") || line.contains("127.0.0.1")) {
                      violations.add(path.getFileName() + ":" + (i + 1) + " -> " + line);
                    }
                    if (line.contains("dummy-key") || line.contains("\"dummy")) {
                      violations.add(
                          path.getFileName() + ":" + (i + 1) + " -> hardcoded credential: " + line);
                    }
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }

    assertTrue(
        violations.isEmpty(),
        "Router production source contains banned literals:\n" + String.join("\n", violations));
  }

  @Test
  @DisplayName("[ERR-129] application/service holds only *Service")
  void applicationServiceNamingConvention() {
    classes()
        .that()
        .resideInAPackage("com.krizaka.orazaka.conversationservice.application.service..")
        .and()
        .areTopLevelClasses()
        .should()
        .haveSimpleNameEndingWith("Service")
        .because(
            "application/service holds only application *Service (capability-oriented, never"
                + " pattern-named *Orchestrator/*Evaluator/*Manager/*Resolver, and not a *Mapper —"
                + " mapping belongs with the code it maps for). Outbound-port impls (*Adapter)"
                + " belong in infrastructure/adapter, config (*Properties) in infrastructure/config"
                + " [ERR-129].")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[ERR-130] domain holds no transport DTOs (*Request/*Response)")
  void domainHasNoTransportDtos() {
    noClasses()
        .that()
        .resideInAPackage("com.krizaka.orazaka.conversationservice.domain..")
        .should()
        .haveSimpleNameEndingWith("Request")
        .orShould()
        .haveSimpleNameEndingWith("Response")
        .because(
            "Transport DTOs (*Request/*Response) belong in the adapter dto packs"
                + " (adapter/rest/dto, adapter/amqp/dto), never in domain [ERR-130].")
        .check(routerClasses);
  }

  @Test
  @DisplayName("[ERR-130] adapter/persistence holds only *Adapter/*Mapper")
  void persistenceAdapterNaming() {
    classes()
        .that()
        .resideInAPackage(
            "com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence..")
        .and()
        .areTopLevelClasses()
        .should()
        .haveSimpleNameEndingWith("Adapter")
        .orShould()
        .haveSimpleNameEndingWith("Mapper")
        .because(
            "infrastructure/adapter/persistence holds outbound-port adapters (*Adapter) and their"
                + " mappers (*Mapper) only — one package, one component kind [ERR-130].")
        .check(routerClasses);
  }

  private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass>
      beFrameworkAnonymousClass() {
    return new ArchCondition<>("be a framework-required anonymous class") {
      @Override
      public void check(
          com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
        // Compiler-synthetic classes (e.g. the enum switch-map `Outer$1` javac emits
        // for a `switch` over an enum) are not developer-authored, so they are exempt.
        boolean isExempt = javaClass.getModifiers().contains(JavaModifier.SYNTHETIC);
        if (!isExempt) {
          isExempt =
              javaClass.getSuperclass().isPresent()
                  && EXEMPT_SUPERTYPES.contains(javaClass.getSuperclass().get().getName());
        }
        if (!isExempt) {
          // Check implemented interfaces — use toErasure() to handle parameterized types
          boolean implementsExempt =
              javaClass.getInterfaces().stream()
                  .anyMatch(
                      i ->
                          EXEMPT_SUPERTYPES.contains(i.getName())
                              || EXEMPT_SUPERTYPES.contains(i.toErasure().getName()));
          isExempt = implementsExempt;
        }
        if (!isExempt) {
          events.add(
              SimpleConditionEvent.violated(
                  javaClass,
                  "Anonymous class <" + javaClass.getName() + "> is not framework-exempt"));
        }
      }
    };
  }

  @Test
  @DisplayName("[ADR-035] no SecurityConfig opens /internal/** or /uploads/**")
  void internalAndMediaSurfacesStayAuthenticated() {
    GovernanceRules.assertNoPermitAllOnInternalOrUploads(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[ADR-035] /internal/v1 demands the SERVICE authority, not merely authentication")
  void internalSurfaceDemandsServiceAuthority() {
    GovernanceRules.assertInternalSurfaceRequiresServiceAuthority(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[AGENTS.md §4] requests run on virtual threads")
  void requestsRunOnVirtualThreads() {
    GovernanceRules.assertVirtualThreadsEnabled(Path.of(System.getProperty("user.dir")));
  }

  @Test
  @DisplayName("[PACK-002] no pack, studio or pack-capability key is a literal in engine code")
  void noPackKeyLiteralsInEngineCode() {
    GovernanceRules.assertNoPackKeyLiterals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[PACK-003] engine code never branches on a pack identifier")
  void noPackKeyConditionalsInEngineCode() {
    GovernanceRules.assertNoPackKeyConditionals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-001] every in-process capability's handler_key has an executor")
  void everyCapabilityHasAnExecutor() {
    GovernanceRules.assertEveryCapabilityHasAnExecutor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-002] every capability's routing_key is drained by a declared worker")
  void everyCapabilityIsDrained() {
    GovernanceRules.assertEveryCapabilityIsDrained(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName(
      "[CFG-001] every type the configuration binder builds has a constructor it can choose")
  void configurationBindsUnambiguously() {
    ConfigBindingRules.assertConfigurationBindsUnambiguously();
    ConfigBindingRules.assertInjectableComponentsHaveOneConstructor();
  }

  @Test
  @DisplayName("[ERR-113] No Environment injection in production beans")
  void noEnvironmentInjection() {
    SourceFileScanner.assertNoEnvironmentInjection(Path.of("src", "main", "java"));
  }

  /** [LOG-001] no logging call takes a prompt, a response body or a message text (ADR-064). */
  @Test
  void noLoggingCallTakesContent() {
    LoggedContentRules.assertNoLoggingCallTakesContent();
  }
}
