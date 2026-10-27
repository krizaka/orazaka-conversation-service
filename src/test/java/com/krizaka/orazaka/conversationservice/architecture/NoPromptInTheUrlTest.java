package com.krizaka.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A prompt is user content: in a URL it reaches access logs, proxies and browser history
 * (orazaka-conversation-service#19). A REST handler takes it in a body, never in a query parameter.
 */
class NoPromptInTheUrlTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.krizaka.orazaka.conversationservice");

  @Test
  @DisplayName(
      "[#19] no GET event stream takes a @RequestParam: user content travels in a POST body")
  void noGetEventStreamTakesARequestParam() {
    noMethods()
        .that()
        .areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
        .should(
            new ArchCondition<JavaMethod>("have a @RequestParam parameter") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                boolean streams =
                    java.util.Arrays.asList(
                            method
                                .getAnnotationOfType(
                                    org.springframework.web.bind.annotation.GetMapping.class)
                                .produces())
                        .contains("text/event-stream");
                boolean has =
                    streams
                        && method.getParameters().stream()
                            .anyMatch(
                                p ->
                                    p.isAnnotatedWith(
                                        org.springframework.web.bind.annotation.RequestParam
                                            .class));
                if (has) {
                  events.add(SimpleConditionEvent.satisfied(method, method.getFullName()));
                }
              }
            })
        .because("a query string reaches access logs and proxies; POST a body instead")
        .allowEmptyShould(true)
        .check(PRODUCTION_CLASSES);
  }
}
