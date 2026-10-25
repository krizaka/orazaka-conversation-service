package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawReviewDtos.Decision;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawReviewDtos.DerivationRequest;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawReviewDtos.DerivationResponse;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawReviewDtos.GoldenResponse;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawReviewDtos.PromotionRequest;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.LawReviewDtos.ProposalResponse;
import com.krizaka.orazaka.tools.application.service.KindDemandService;
import com.krizaka.orazaka.tools.application.service.KindDerivationService;
import com.krizaka.orazaka.tools.application.service.RuleProposalReviewService;
import com.krizaka.orazaka.tools.application.service.RuleProposalService;
import com.krizaka.orazaka.tools.application.service.RulesetPromotionService;
import com.krizaka.orazaka.tools.domain.model.law.KindDemand;
import com.krizaka.orazaka.tools.domain.model.law.LawReviewRefusal.Code;
import com.krizaka.orazaka.tools.domain.model.law.PromotionResult;
import com.krizaka.orazaka.tools.domain.model.law.ProposalStatus;
import com.krizaka.orazaka.tools.domain.model.law.ProposedRule;
import com.krizaka.users.domain.model.User;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue of ADR-089 (§5, §8): the proposals and their decisions, the golden cases and the
 * promotion of a kind, the derivations, and the demand. Administrators only ({@code
 * SecurityConfig}), every write audited ({@code OperatorAuditFilter}, ADR-085). Every rule is the
 * use cases' (in orazaka-tools); this controller only translates HTTP.
 */
@RestController
@RequestMapping(LawReviewController.BASE)
public class LawReviewController {

  static final String BASE = "/api/v1/admin/law";

  private final RuleProposalService proposals;
  private final RuleProposalReviewService review;
  private final RulesetPromotionService promotion;
  private final KindDemandService demand;
  private final ObjectProvider<KindDerivationService> derivations;

  public LawReviewController(
      RuleProposalService proposals,
      RuleProposalReviewService review,
      RulesetPromotionService promotion,
      KindDemandService demand,
      ObjectProvider<KindDerivationService> derivations) {
    this.proposals = Objects.requireNonNull(proposals, "RuleProposalService");
    this.review = Objects.requireNonNull(review, "RuleProposalReviewService");
    this.promotion = Objects.requireNonNull(promotion, "RulesetPromotionService");
    this.demand = Objects.requireNonNull(demand, "KindDemandService");
    this.derivations = Objects.requireNonNull(derivations, "KindDerivationService");
  }

  /**
   * The queue.
   *
   * @param status only this status, all when absent
   * @param kindId only this kind, all when absent
   * @return the proposals, newest first
   */
  @GetMapping("/proposals")
  public List<ProposalResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kindId) {
    ProposalStatus wanted = LawReviewProblem.translate(() -> status(status));
    return proposals.list(wanted, kindId).stream().map(ProposalResponse::of).toList();
  }

  /**
   * One proposal.
   *
   * @param id the proposal
   * @return it, or {@code 404 proposal-not-found}
   */
  @GetMapping("/proposals/{id}")
  public ProposalResponse get(@PathVariable UUID id) {
    return proposals
        .find(id)
        .map(ProposalResponse::of)
        .orElseThrow(() -> LawReviewProblem.of(Code.PROPOSAL_NOT_FOUND, "no proposal " + id));
  }

  /**
   * Edits a proposal: its next revision, vetted again.
   *
   * @param id the proposal edited
   * @param rule the rule as the reviewer wrote it
   * @return the new revision
   */
  @PutMapping("/proposals/{id}")
  public ProposalResponse edit(@PathVariable UUID id, @RequestBody ProposedRule rule) {
    return ProposalResponse.of(LawReviewProblem.translate(() -> review.edit(id, rule)));
  }

  /**
   * Approves a proposal.
   *
   * @param id the proposal
   * @param decision an optional note
   * @param user the administrator
   * @return the proposal, approved
   */
  @PostMapping("/proposals/{id}/approve")
  public ProposalResponse approve(
      @PathVariable UUID id,
      @RequestBody(required = false) Decision decision,
      @AuthenticationPrincipal User user) {
    String note = decision == null ? null : decision.note();
    return ProposalResponse.of(
        LawReviewProblem.translate(() -> proposals.approve(id, actor(user), note)));
  }

  /**
   * Rejects a proposal, with its reason.
   *
   * @param id the proposal
   * @param decision the reason, required
   * @param user the administrator
   * @return the proposal, rejected
   */
  @PostMapping("/proposals/{id}/reject")
  public ProposalResponse reject(
      @PathVariable UUID id,
      @RequestBody(required = false) Decision decision,
      @AuthenticationPrincipal User user) {
    String reason = decision == null ? null : decision.reason();
    return ProposalResponse.of(
        LawReviewProblem.translate(() -> proposals.reject(id, actor(user), reason)));
  }

  /**
   * The golden cases of a kind against the minimum of promotion.
   *
   * @param kindId the kind
   * @return the cases and whether they meet {@code law.review.min-golden-cases}
   */
  @GetMapping("/kinds/{kindId}/golden")
  public GoldenResponse golden(@PathVariable String kindId) {
    return GoldenResponse.of(LawReviewProblem.translate(() -> promotion.goldenCases(kindId)));
  }

  /**
   * Opens the packs pull request of approved proposals.
   *
   * @param kindId the kind, which is the ruleset id
   * @param request the proposals, the in-force day and an optional revision note
   * @return the pull request
   */
  @PostMapping("/kinds/{kindId}/promote")
  public PromotionResult promote(
      @PathVariable String kindId, @RequestBody PromotionRequest request) {
    return LawReviewProblem.translate(
        () ->
            promotion.promote(
                kindId,
                request.proposalIds(),
                day(request.effectiveFrom(), "effectiveFrom"),
                request.revisionNote()));
  }

  /**
   * Starts the derivation of a kind.
   *
   * @param request the jurisdiction, the kind, the day and the language
   * @param user the administrator, who the run is billed to
   * @return the run and what it wrote
   */
  @PostMapping("/derivations")
  public DerivationResponse derive(
      @RequestBody DerivationRequest request, @AuthenticationPrincipal User user) {
    KindDerivationService service = derivations.getIfAvailable();
    if (service == null) {
      throw LawReviewProblem.of(
          Code.LAW_DERIVATION_OFF, "derivation is not wired here (orazaka.law.derivation.enabled)");
    }
    return DerivationResponse.of(
        LawReviewProblem.translate(
            () ->
                service.start(
                    request.jurisdiction(),
                    request.kindId(),
                    day(request.asOf(), "asOf"),
                    request.language(),
                    actor(user))));
  }

  /**
   * The unsupported and unknown kinds, counted.
   *
   * @return the most asked first
   */
  @GetMapping("/demand")
  public List<KindDemand> demand() {
    return demand.list();
  }

  private static String actor(User user) {
    if (user == null) {
      throw new IllegalArgumentException("a review names its reviewer");
    }
    return user.id().toString();
  }

  private static ProposalStatus status(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return ProposalStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("unknown status " + status, unknown);
    }
  }

  static LocalDate day(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestProblem(name + " is required (yyyy-MM-dd)");
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidRequestProblem(name + " is not a day (yyyy-MM-dd): " + value);
    }
  }
}
