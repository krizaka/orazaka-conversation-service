package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import java.util.regex.Pattern;

/**
 * The operator surface of the conversation service (ADR-085): the paths whose writes {@link
 * OperatorAuditFilter} records. Every write under {@code /api/v1/admin} must be on it; {@code
 * AdminWritesAreAuditedTest} [AUDIT-001] fails on one it misses.
 */
public final class OperatorAuditPaths {

  /** The model registry, the app configuration and the law review queue (ADR-089 §8). */
  private static final Pattern OPERATOR_PATH =
      Pattern.compile("/api/v1/(?:models(?:/.*)?|admin/(?:app-config|law)(?:/.*)?)");

  private OperatorAuditPaths() {}

  /**
   * Whether a write to this path leaves an audit line.
   *
   * @param path the request path, or a mapping's path template
   * @return true when the path is on the operator surface
   */
  public static boolean audits(String path) {
    return path != null && OPERATOR_PATH.matcher(path).matches();
  }
}
