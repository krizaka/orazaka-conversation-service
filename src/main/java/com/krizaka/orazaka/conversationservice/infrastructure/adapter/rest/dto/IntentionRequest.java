package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Transport DTO for {@code POST /api/v1/intent} — the App-Factory ingress. The router maps it to a
 * business {@code Intention} and hands it to the {@code UseCaseDispatcher}.
 *
 * @param capability Target capability name (CHAT | IMAGE | AGENT | STUDIO), case-insensitive
 *     (required).
 * @param goal Optional short business intent.
 * @param prompt The user prompt / generation prompt. Required for every capability except {@code
 *     STUDIO}, whose instructions come from its blueprint rather than from the caller.
 * @param sessionId Optional conversation/session id.
 * @param size Optional size hint for image generation (e.g. {@code "1024x1024"}).
 * @param installationId The installed Studio to run. Required for {@code STUDIO} and meaningless
 *     otherwise (ADR-034 §9.2).
 * @param inputs The Studio run's answers to its blueprint's input schema; never null.
 */
public record IntentionRequest(
    String capability,
    String goal,
    String prompt,
    String sessionId,
    String size,
    String installationId,
    Map<String, Object> inputs) {

  /** The one capability whose instructions come from a blueprint rather than from the caller. */
  private static final String STUDIO = "STUDIO";

  /**
   * Compact canonical constructor.
   *
   * <p>The required field genuinely differs by capability, and the record is the only place that
   * knows both — so it branches here rather than leaving each call site to remember (ERR-106/116).
   * A Studio run carries no prompt because its prompts live in the blueprint an admin published; a
   * chat turn carries no installation because there is nothing installed to run.
   */
  public IntentionRequest {
    Objects.requireNonNull(capability, "capability must not be null");
    if (capability.isBlank()) {
      throw new IllegalArgumentException("capability must not be blank");
    }
    if (STUDIO.equals(capability.trim().toUpperCase(Locale.ROOT))) {
      if (installationId == null || installationId.isBlank()) {
        throw new IllegalArgumentException("a STUDIO intention must name an installationId");
      }
    } else {
      Objects.requireNonNull(prompt, "prompt must not be null");
      if (prompt.isBlank()) {
        throw new IllegalArgumentException("prompt must not be blank");
      }
    }
    inputs = (inputs == null) ? Map.of() : Map.copyOf(inputs);
  }

  /**
   * Backwards-compatible constructor for the non-Studio capabilities.
   *
   * @param capability the target capability
   * @param goal optional business intent
   * @param prompt the user prompt
   * @param sessionId optional session id
   * @param size optional image size hint
   */
  public IntentionRequest(
      String capability, String goal, String prompt, String sessionId, String size) {
    this(capability, goal, prompt, sessionId, size, null, Map.of());
  }
}
