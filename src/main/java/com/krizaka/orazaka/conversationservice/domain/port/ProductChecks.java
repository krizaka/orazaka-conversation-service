package com.krizaka.orazaka.conversationservice.domain.port;

import com.krizaka.orazaka.core.domain.model.checks.CheckCall;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckResult;
import com.krizaka.orazaka.core.domain.model.security.Actor;
import java.util.Optional;

/**
 * The checks a product asks for over HTTP (orazaka-ai-engine#105): run one, collect a pending one.
 * Everything that decides stays in the engine's one door (consent, key, budget, audit); this port
 * adds what only a caller has: a verified actor with an allowed role (ADR-091), a rate limit, and
 * the tenant a pending reference belongs to.
 */
public interface ProductChecks {

  /**
   * The answer of a collected check.
   *
   * @param purpose what the check was for
   * @param result the closed result
   */
  record Collected(CheckPurpose purpose, CheckResult result) {}

  /**
   * Whether the actor may use the door at all.
   *
   * @param actor the verified actor
   * @return {@code true} when it holds one of the allowed roles
   */
  boolean permits(Actor actor);

  /**
   * Takes one call of the actor's rate window.
   *
   * @param actor the verified actor
   * @return {@code 0} when it may go on, else the seconds to wait
   */
  long throttle(Actor actor);

  /**
   * Starts a check.
   *
   * @param actor the verified actor
   * @param purpose court records or credit
   * @param call the subject and the run
   * @param consented the caller's statement that the subject consented to this purpose
   * @return the closed result
   */
  CheckResult submit(Actor actor, CheckPurpose purpose, CheckCall call, boolean consented);

  /**
   * Collects a pending check, if the actor's tenant submitted it.
   *
   * @param actor the verified actor
   * @param providerRef the reference a submission answered
   * @param subjectId the opaque id of the subject the check was for
   * @param consented the caller's statement that the consent still stands
   * @return the answer, empty when the tenant never received this reference
   */
  Optional<Collected> collect(Actor actor, String providerRef, String subjectId, boolean consented);
}
