package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.tools.domain.model.law.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The bodies of the admin law API (ADR-089 §8), in the shapes the console reads. */
public final class LawReviewDtos {

  private LawReviewDtos() {}

  /**
   * A review decision: the note of an approval, the reason of a rejection.
   *
   * @param note the approval's optional note
   * @param reason the rejection's reason
   */
  public record Decision(String note, String reason) {}

  /**
   * A promotion.
   *
   * @param proposalIds the approved proposals, in the ruleset's order
   * @param effectiveFrom the law's in-force day, {@code yyyy-MM-dd}
   * @param revisionNote what this revision corrects, optional for a new version
   */
  public record PromotionRequest(
      List<UUID> proposalIds, String effectiveFrom, String revisionNote) {}

  /**
   * A derivation an administrator starts.
   *
   * @param jurisdiction the jurisdiction
   * @param kindId the kind
   * @param asOf the day the law must be in force, {@code yyyy-MM-dd}
   * @param language the language of the question, {@code fr} by default
   */
  public record DerivationRequest(
      String jurisdiction, String kindId, String asOf, String language) {}

  /**
   * One proposal of the queue.
   *
   * @param id the row
   * @param jurisdiction the jurisdiction
   * @param kindId the kind
   * @param ruleId the rule
   * @param proposalRevision its revision
   * @param status where it stands
   * @param rule the rule, with its law blocks and fixtures
   * @param vetReport what the vet found
   * @param derivationRunId the derivation
   * @param reviewedBy who reviewed it
   * @param reviewedAt when
   * @param decisionNote the note or the reason
   * @param packsPrUrl the pull request that carries it
   * @param createdAt when it was written
   */
  public record ProposalResponse(
      UUID id,
      String jurisdiction,
      String kindId,
      String ruleId,
      int proposalRevision,
      ProposalStatus status,
      ProposedRule rule,
      Vet vetReport,
      UUID derivationRunId,
      String reviewedBy,
      Instant reviewedAt,
      String decisionNote,
      String packsPrUrl,
      Instant createdAt) {

    /**
     * The response of a row.
     *
     * @param p the row
     * @return its body
     */
    public static ProposalResponse of(RuleProposal p) {
      return new ProposalResponse(
          p.id(),
          p.jurisdiction(),
          p.kindId(),
          p.ruleId(),
          p.proposalRevision(),
          p.status(),
          p.rule(),
          Vet.of(p.vetReport()),
          p.derivationRunId(),
          p.reviewedBy(),
          p.reviewedAt(),
          p.decisionNote(),
          p.packsPrUrl(),
          p.createdAt());
    }
  }

  /**
   * A vet report, with whether it passed.
   *
   * @param vetVersion the vet's version
   * @param asOf the day the rule was vetted on
   * @param passed true when nothing was found
   * @param findings what was found
   */
  public record Vet(String vetVersion, String asOf, boolean passed, List<VetFinding> findings) {

    static Vet of(VetReport report) {
      return report == null
          ? null
          : new Vet(report.vetVersion(), report.asOf(), report.passed(), report.findings());
    }
  }

  /**
   * The golden cases of a kind.
   *
   * @param kindId the kind
   * @param required {@code law.review.min-golden-cases}
   * @param documents how many cases
   * @param compliant how many clean
   * @param defective how many with known errors
   * @param blank how many blank forms
   * @param met whether promotion may go on
   * @param cases the cases
   */
  public record GoldenResponse(
      String kindId,
      int required,
      int documents,
      long compliant,
      long defective,
      long blank,
      boolean met,
      List<Case> cases) {

    /**
     * The response of the cases.
     *
     * @param golden the cases
     * @return their body
     */
    public static GoldenResponse of(GoldenCases golden) {
      return new GoldenResponse(
          golden.kindId(),
          golden.required(),
          golden.cases().size(),
          golden.compliant(),
          golden.defective(),
          golden.blank(),
          golden.met(),
          golden.cases().stream().map(Case::of).toList());
    }

    /**
     * One case.
     *
     * @param name its name
     * @param form {@code BLANK} or {@code FILLED}
     */
    public record Case(String name, String form) {
      static Case of(GoldenCase c) {
        return new Case(c.name(), c.blank() ? "BLANK" : "FILLED");
      }
    }
  }

  /**
   * A derivation started.
   *
   * @param runId the run
   * @param outcome how it ended
   * @param proposals the proposals written for review
   * @param rejected the ones the vet refused
   * @param creditsSpent the credits it spent
   */
  public record DerivationResponse(
      UUID runId, String outcome, long proposals, long rejected, long creditsSpent) {

    /**
     * The response of a derivation.
     *
     * @param started the derivation
     * @return its body
     */
    public static DerivationResponse of(DerivationStarted started) {
      return new DerivationResponse(
          started.runId(),
          started.result().outcome().name(),
          started.proposed(),
          started.rejected(),
          started.result().creditsSpent());
    }
  }
}
