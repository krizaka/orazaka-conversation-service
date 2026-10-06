package com.orazaka.conversationservice.application.service;

import com.orazaka.conversationservice.domain.model.gate.AuthGate;
import com.orazaka.conversationservice.domain.model.gate.CostShieldGate;
import com.orazaka.conversationservice.domain.model.gate.SecOpsGate;
import com.orazaka.conversationservice.domain.model.gate.StaticGate;
import com.orazaka.conversationservice.domain.model.intent.IntentToken;
import com.orazaka.conversationservice.infrastructure.config.M2mJwtProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Level 0 gate evaluator — exhaustive switch over the sealed {@link StaticGate} hierarchy.
 *
 * <p>CRITICAL: This service performs ZERO database interaction. It evaluates static gate conditions
 * and produces a signed {@link IntentToken} using HMAC-SHA256.
 */
@Service
public final class GateService {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Duration TOKEN_TTL = Duration.ofMinutes(5);

  private final byte[] signingKey;

  public GateService(M2mJwtProperties properties) {
    this.signingKey = properties.intentTokenSecret().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Evaluate a {@link StaticGate} and produce a signed {@link IntentToken}.
   *
   * @param gate the gate to evaluate
   * @return a signed intent token encoding the gate decision
   */
  public IntentToken evaluate(StaticGate gate) {
    String decision =
        switch (gate) {
          case AuthGate auth -> evaluateAuth(auth);
          case SecOpsGate secOps -> evaluateSecOps(secOps);
          case CostShieldGate costShield -> evaluateCostShield(costShield);
        };

    Instant now = Instant.now();
    Instant expiry = now.plus(TOKEN_TTL);
    String signature = sign(decision + ":" + now.toEpochMilli());

    return new IntentToken(signature, now, expiry, decision);
  }

  private static String evaluateAuth(AuthGate auth) {
    if (!auth.verified()) {
      return "AUTH_MFA_REQUIRED";
    }
    if (!auth.scopes().contains("intent:route")) {
      return "AUTH_SCOPE_DENIED";
    }
    return "AUTH_PASS";
  }

  private static String evaluateSecOps(SecOpsGate secOps) {
    return switch (secOps.clearance()) {
      case "L3" -> "SECOPS_FULL_ACCESS";
      case "L2" -> "SECOPS_RESTRICTED";
      case "L1" -> "SECOPS_READ_ONLY";
      default -> "SECOPS_DENIED";
    };
  }

  private static String evaluateCostShield(CostShieldGate costShield) {
    if (costShield.memoryPressure() > 0.85) {
      return costShield.cloudFallback() ? "COST_SHIELD_CLOUD" : "COST_SHIELD_BLOCKED";
    }
    if (costShield.budgetRemaining() < 0.10) {
      return "COST_SHIELD_BUDGET_LOW";
    }
    return "COST_SHIELD_LOCAL";
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
      byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC signing failed", e);
    }
  }
}
