package com.orazaka.conversationservice.domain.model.gate;

/**
 * Security operations gate — enforces SecOps policy compliance at Level 0.
 *
 * @param operatorId Operator principal performing the SecOps action.
 * @param clearance Security clearance level (e.g., "L1", "L2", "L3").
 * @param auditTrailId Unique correlation ID for the audit trail.
 */
public record SecOpsGate(String operatorId, String clearance, String auditTrailId)
    implements StaticGate {

  public SecOpsGate {
    if (operatorId == null || operatorId.isBlank()) {
      throw new IllegalArgumentException("SecOpsGate requires a non-blank operatorId");
    }
    if (clearance == null || clearance.isBlank()) {
      throw new IllegalArgumentException("SecOpsGate requires a non-blank clearance level");
    }
    if (auditTrailId == null || auditTrailId.isBlank()) {
      throw new IllegalArgumentException("SecOpsGate requires a non-blank auditTrailId");
    }
  }
}
