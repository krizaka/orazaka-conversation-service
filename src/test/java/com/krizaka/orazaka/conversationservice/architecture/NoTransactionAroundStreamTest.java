package com.krizaka.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A transaction holds a pooled connection until the method returns: never around a stream. */
class NoTransactionAroundStreamTest {

  private static final JavaClasses MAIN_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.krizaka.orazaka.conversationservice");

  @Test
  @DisplayName("[#10] no @Transactional method returns a stream (Flux, Mono, SseEmitter, Stream)")
  void transactionalNeverReturnsAStream() {
    noMethods()
        .that()
        .areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
        .should()
        .haveRawReturnType(Flux.class)
        .orShould()
        .haveRawReturnType(Mono.class)
        .orShould()
        .haveRawReturnType(SseEmitter.class)
        .orShould()
        .haveRawReturnType(java.util.stream.Stream.class)
        .because("the connection would stay out of the pool for the whole stream (#10)")
        .allowEmptyShould(true)
        .check(MAIN_CLASSES);
  }
}
