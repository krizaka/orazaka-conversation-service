package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.orazaka.conversationservice.domain.port.ProductCheckAudit;
import com.krizaka.orazaka.conversationservice.domain.port.ProductChecks;
import com.krizaka.orazaka.core.application.checks.CheckRouter;
import com.krizaka.orazaka.core.application.checks.ExternalCheckService;
import com.krizaka.orazaka.core.domain.model.checks.CheckCall;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckRefusal;
import com.krizaka.orazaka.core.domain.model.checks.CheckResult;
import com.krizaka.orazaka.core.domain.model.checks.CheckSubject;
import com.krizaka.orazaka.core.domain.model.security.Actor;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckKeyStore;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckMeter;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckSubjectPepper;
import com.krizaka.orazaka.core.infrastructure.support.CoreException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The checks a product asks for over HTTP (orazaka-ai-engine#105). Everything that decides stays in
 * the engine's one door, {@link ExternalCheckService}: consent per purpose, key, budget, provider,
 * audit. This service adds what only a caller has: a verified actor holding one of the allowed
 * roles (ADR-091), a rate limit per actor, and the tenant under which every line is audited and a
 * pending reference is collected.
 *
 * <p>The consent is the caller's statement for this subject and this purpose, carried by the
 * request ({@code consented}); nothing else grants a check here, no stored consent and no default.
 * Without a provider row, a key or a sealed pepper the answer is {@code REFUSED / NOT_CONFIGURED}
 * or {@code NO_KEY}: never a made-up result.
 */
public class ProductCheckService implements ProductChecks {

  private final CheckRouter router;
  private final CheckKeyStore keys;
  private final CheckMeter meter;
  private final CheckSubjectPepper pepper;
  private final ProductCheckAudit audit;
  private final CheckRateLimitService limiter;
  private final Set<String> allowedRoles;
  private final Clock clock;

  /**
   * @param router the provider routing (rows by handler key)
   * @param keys the provider keys
   * @param meter the {@code CALL} metering
   * @param pepper the sealed pepper of the subject reference
   * @param audit the audit, per tenant
   * @param limiter the per-actor rate limit
   * @param allowedRoles the roles any of which may use the door
   * @param clock the clock
   */
  public ProductCheckService(
      CheckRouter router,
      CheckKeyStore keys,
      CheckMeter meter,
      CheckSubjectPepper pepper,
      ProductCheckAudit audit,
      CheckRateLimitService limiter,
      Set<String> allowedRoles,
      Clock clock) {
    this.router = Objects.requireNonNull(router, "router must not be null");
    this.keys = Objects.requireNonNull(keys, "keys must not be null");
    this.meter = Objects.requireNonNull(meter, "meter must not be null");
    this.pepper = Objects.requireNonNull(pepper, "pepper must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.limiter = Objects.requireNonNull(limiter, "limiter must not be null");
    this.allowedRoles = Set.copyOf(allowedRoles);
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Whether the actor may use the door at all.
   *
   * @param actor the verified actor
   * @return {@code true} when it holds one of the allowed roles
   */
  @Override
  public boolean permits(Actor actor) {
    return actor.holdsAny(allowedRoles);
  }

  /**
   * The seconds the actor must wait, taking one call of its window.
   *
   * @param actor the verified actor
   * @return {@code 0} when it may go on
   */
  @Override
  public long throttle(Actor actor) {
    return limiter.tryAcquire(actor.tenant() + "/" + actor.subject());
  }

  /**
   * Starts a check.
   *
   * @param actor the verified actor
   * @param purpose court records or credit
   * @param call the subject and the run
   * @param consented the caller's statement that the subject consented to this purpose
   * @return the closed result
   */
  @Override
  public CheckResult submit(Actor actor, CheckPurpose purpose, CheckCall call, boolean consented) {
    ExternalCheckService door = door(actor, purpose, call.subject().subjectId(), consented);
    try {
      return purpose == CheckPurpose.CREDIT
          ? door.verifyCredit(call)
          : door.verifyCourtRecords(call);
    } catch (CoreException off) {
      // No sealed pepper: the engine will not name a subject, so no check runs.
      return CheckResult.refused(CheckRefusal.NOT_CONFIGURED);
    }
  }

  /**
   * Collects a pending check, if the tenant submitted it.
   *
   * @param actor the verified actor
   * @param providerRef the reference a submission answered
   * @param subjectId the opaque id of the subject the check was for
   * @param consented the caller's statement that the consent still stands
   * @return the answer, empty when the tenant never received this reference
   */
  @Override
  public Optional<Collected> collect(
      Actor actor, String providerRef, String subjectId, boolean consented) {
    return audit
        .submitted(actor.tenant(), providerRef)
        .map(
            submitted -> {
              ExternalCheckService door = door(actor, submitted.purpose(), subjectId, consented);
              // The provider's poll reads no name: the subject carries its id only.
              CheckCall call =
                  new CheckCall(
                      actor.subject(),
                      submitted.runId(),
                      new CheckSubject(subjectId, "-", "-", null));
              try {
                return new Collected(
                    submitted.purpose(),
                    door.collect(submitted.provider(), submitted.purpose(), call, providerRef));
              } catch (CoreException off) {
                return new Collected(
                    submitted.purpose(), CheckResult.refused(CheckRefusal.NOT_CONFIGURED));
              }
            });
  }

  private ExternalCheckService door(
      Actor actor, CheckPurpose purpose, String subjectId, boolean consented) {
    return new ExternalCheckService(
        router,
        (subject, asked) -> consented && asked == purpose && subjectId.equals(subject),
        keys,
        audit.forTenant(actor.tenant()),
        meter,
        pepper,
        clock);
  }
}
