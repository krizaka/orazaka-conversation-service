package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.core.domain.model.checks.CheckCategory;
import com.krizaka.orazaka.core.domain.model.checks.CheckOutcome;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckRefusal;
import com.krizaka.orazaka.core.domain.model.checks.CheckResult;
import java.util.UUID;

/**
 * The closed answer of a check: an outcome, never a record. Same fields as the engine's {@code
 * CheckResult}.
 *
 * @param purpose what the check was for
 * @param outcome MATCH, NO_MATCH, PENDING, PROVIDER_ERROR, TIMEOUT or REFUSED
 * @param refusal why it was refused (NO_CONSENT, NO_KEY, BUDGET_EXHAUSTED, NOT_CONFIGURED), or null
 * @param category the court category on a match, else NONE
 * @param providerRef the provider's opaque reference (collect a PENDING check with it), or null
 * @param runId the run the product gave, or null
 */
public record CheckResponse(
    CheckPurpose purpose,
    CheckOutcome outcome,
    CheckRefusal refusal,
    CheckCategory category,
    String providerRef,
    UUID runId) {

  /**
   * @param purpose what the check was for
   * @param result the engine's result
   * @param runId the run, or null
   * @return the response
   */
  public static CheckResponse of(CheckPurpose purpose, CheckResult result, UUID runId) {
    return new CheckResponse(
        purpose,
        result.outcome(),
        result.refusal(),
        result.category(),
        result.providerRef(),
        runId);
  }
}
