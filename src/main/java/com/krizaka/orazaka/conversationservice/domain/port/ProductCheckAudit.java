package com.krizaka.orazaka.conversationservice.domain.port;

import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckAuditLog;
import java.util.Optional;
import java.util.UUID;

/**
 * The audit of the checks a product asks for through the HTTP door (orazaka-ai-engine#105): one
 * line per call, refused or not, written under the caller's tenant, and the memory of what each
 * provider reference was asked for, so that a pending check is collected only by the tenant that
 * submitted it.
 */
public interface ProductCheckAudit {

  /** What a provider reference was submitted for. */
  record Submitted(String provider, CheckPurpose purpose, UUID runId) {}

  /**
   * The audit log of one tenant: every line it writes carries the tenant.
   *
   * @param tenant the caller's tenant
   * @return the log the engine's door writes to
   */
  CheckAuditLog forTenant(String tenant);

  /**
   * What the tenant submitted under a provider reference.
   *
   * @param tenant the caller's tenant
   * @param providerRef the provider's opaque reference
   * @return the submission, empty when the tenant never received that reference
   */
  Optional<Submitted> submitted(String tenant, String providerRef);
}
