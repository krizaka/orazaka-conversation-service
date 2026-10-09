package com.krizaka.orazaka.conversationservice.domain.model.gate;

/**
 * Cost shield gate — evaluates resource budget thresholds at Level 0.
 *
 * @param budgetRemaining Fraction of budget remaining (0.0–1.0).
 * @param memoryPressure Current JVM memory pressure ratio (0.0–1.0).
 * @param cloudFallback Whether cloud fallback is pre-authorized.
 */
public record CostShieldGate(double budgetRemaining, double memoryPressure, boolean cloudFallback)
    implements StaticGate {

  public CostShieldGate {
    if (budgetRemaining < 0.0 || budgetRemaining > 1.0) {
      throw new IllegalArgumentException(
          "budgetRemaining must be in [0.0, 1.0], got: " + budgetRemaining);
    }
    if (memoryPressure < 0.0 || memoryPressure > 1.0) {
      throw new IllegalArgumentException(
          "memoryPressure must be in [0.0, 1.0], got: " + memoryPressure);
    }
  }
}
