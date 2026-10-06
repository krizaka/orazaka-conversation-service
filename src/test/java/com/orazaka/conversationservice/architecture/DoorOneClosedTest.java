package com.orazaka.conversationservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Door 1 is closed: no HTTP entry of this service creates a job (ADR-068 §6.2).
 *
 * <p><b>Why this test exists and the integration test could not replace it.</b> {@code
 * DoorOneControlsIT} asserts what the run path produces — a run row, a class, a trail, one
 * settlement. It asserts nothing about what else might exist: restore {@code
 * MediaGenerationController} and every one of its assertions still passes, because the run path is
 * untouched by a second door being cut beside it. The §6.4 plant is what showed that, and this is
 * the half it was missing.
 *
 * <p>The two ways to mint a job are named rather than inferred: {@code JobService.createJob} writes
 * the row and {@code JobQueuePublisherService.publish} queues the command. A
 * {@code @RequestMapping} class that reaches either is a capability invocation with no run behind
 * it, and therefore no data class, no retention by class, no audit trail and no scope guard — which
 * is what door 1 was, not an implementation detail of it.
 *
 * <p>{@code publishApproval} is deliberately <b>not</b> covered: it releases a job that already
 * exists, written when an automation ran, and approval is the gate in front of it rather than a way
 * in.
 *
 * <p>This is the surface of <b>this</b> service. The repository-wide rule — no capability reachable
 * other than through a run, in any service, so the bypass cannot grow back anywhere — is M5's, and
 * belongs in {@code orazaka-test-support} beside the other governance rules.
 */
class DoorOneClosedTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.orazaka.conversationservice");

  @Test
  @DisplayName("[ADR-068] no REST controller of this service creates a job")
  void noRestEntryCreatesAJob() {
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice.infrastructure.adapter.rest..")
        .should()
        .callMethod(
            com.orazaka.core.domain.ports.inbound.JobService.class,
            "createJob",
            String.class,
            String.class,
            java.util.Map.class,
            com.orazaka.jobs.domain.model.DataClass.class)
        .orShould()
        .callMethod(
            com.orazaka.core.domain.ports.inbound.JobService.class,
            "createJob",
            String.class,
            String.class,
            String.class,
            java.util.Map.class,
            com.orazaka.jobs.domain.model.DataClass.class)
        .orShould()
        .callMethod(
            com.orazaka.conversationservice.application.service.JobQueuePublisherService.class,
            "publish",
            com.orazaka.jobs.domain.model.JobCommand.class)
        .because(
            "a capability invoked over HTTP without a run carries none of the controls a run"
                + " carries: no data class from its pack, no retention by that class, no audit"
                + " trail and no scope guard. That is what door 1 was (ADR-068 §5), and a"
                + " controller that mints a job is door 1 coming back")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("[ADR-068] no application service of this service publishes a job command either")
  void noApplicationServicePublishesAJobCommand() {
    // The controllers delegated: MediaGenerationController through MediaJobService, the analysis
    // paths through JobSubmissionService. Deleting the controllers and leaving the services would
    // have left door 1 one @PostMapping away, so the delegates are covered too — everything except
    // the publisher itself and the automation approval path that legitimately uses it.
    noClasses()
        .that()
        .resideInAPackage("com.orazaka.conversationservice.application.service")
        .and()
        .haveSimpleNameNotEndingWith("JobQueuePublisherService")
        .should()
        .callMethod(
            com.orazaka.conversationservice.application.service.JobQueuePublisherService.class,
            "publish",
            com.orazaka.jobs.domain.model.JobCommand.class)
        .because(
            "the only producer of a job command is the studio outbox (ADR-067): a run's step"
                + " dispatch, durable with the state that describes it")
        .check(PRODUCTION_CLASSES);
  }
}
