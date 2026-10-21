package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every operator write of the conversation service (model registry, app configuration) leaves one
 * audit line (ADR-085): who (the administrator's id and label), what ({@code METHOD /path/{id}} —
 * the closed set of admin endpoints), which target, in which request, and how it ended ({@code ok},
 * {@code refused} for a 400/403/409/422, {@code failed} otherwise). It runs after Spring Security,
 * so a call refused for a missing role never reaches it, and it never carries a body: ids only. A
 * store that fails never fails the request.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class OperatorAuditFilter extends OncePerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(OperatorAuditFilter.class);
  private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final Set<Integer> REFUSALS = Set.of(400, 403, 409, 422);

  /**
   * The operator surface of the engine's conversation service: the model registry and the app
   * configuration.
   */
  private static final Pattern OPERATOR_PATH =
      Pattern.compile("/api/v1/(?:models(?:/.*)?|admin/app-config(?:/.*)?)");

  private static final Pattern ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+");

  private final OperatorAuditStore store;

  OperatorAuditFilter(OperatorAuditStore store) {
    this.store = store;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(request, response);
    String path = request.getRequestURI();
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!WRITES.contains(request.getMethod())
        || !OPERATOR_PATH.matcher(path).matches()
        || auth == null
        || auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
      return;
    }
    try {
      store.append(line(request.getMethod(), path, auth, response.getStatus()));
    } catch (RuntimeException failed) {
      logger.warn(
          "The operator audit line of {} {} could not be written",
          request.getMethod(),
          path,
          failed);
    }
  }

  private static OperatorAuditStore.Line line(
      String method, String path, Authentication auth, int status) {
    String[] segments = path.split("/");
    String target = null;
    StringBuilder pattern = new StringBuilder();
    for (String segment : segments) {
      if (segment.isEmpty()) {
        continue;
      }
      boolean isId = ID.matcher(segment).matches();
      pattern.append('/').append(isId ? "{id}" : segment);
      if (isId) {
        target = segment;
      }
    }
    // A named resource (/plans/pro) is the target of a write to it: the last segment of a write on
    // a collection item.
    if (target == null && segments.length > 0 && !"POST".equals(method)) {
      target = segments[segments.length - 1];
    }
    if (target != null && !ID.matcher(target).matches()) {
      int last = pattern.lastIndexOf("/");
      pattern.replace(last, pattern.length(), "/{id}");
    }
    String outcome = status < 400 ? "ok" : REFUSALS.contains(status) ? "refused" : "failed";
    // This service's principal is the hydrated User record: its id and e-mail, never its toString
    // (preferences, tiers).
    String actorId = auth.getName();
    String label = auth.getName();
    if (auth.getPrincipal() instanceof com.krizaka.users.domain.model.User user) {
      actorId = user.id().toString();
      label = user.email();
    } else if (auth instanceof JwtAuthenticationToken jwt) {
      label = claim(jwt, "email", auth.getName());
    }
    return new OperatorAuditStore.Line(
        actorId, label, method + " " + pattern, target, MDC.get("requestId"), outcome);
  }

  private static String claim(JwtAuthenticationToken jwt, String name, String fallback) {
    String value = jwt.getToken().getClaimAsString(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
