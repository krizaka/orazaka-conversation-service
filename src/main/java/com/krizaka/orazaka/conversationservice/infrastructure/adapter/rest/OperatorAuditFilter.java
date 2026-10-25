package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.users.domain.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
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
import org.springframework.web.servlet.HandlerMapping;

/**
 * Every operator write of the conversation service (model registry, app configuration, the law
 * review queue) leaves one audit line (ADR-085): who (the administrator's id and label), what
 * ({@code METHOD /path/{id}} — the closed set of admin endpoints), which target, in which request,
 * and how it ended ({@code ok}, {@code refused} for a 400/403/409/422, {@code failed} otherwise).
 * It runs after Spring Security, so a call refused for a missing role never reaches it, and it
 * never carries a body: ids only. A store that fails never fails the request.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class OperatorAuditFilter extends OncePerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(OperatorAuditFilter.class);
  private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final Set<Integer> REFUSALS = Set.of(400, 403, 409, 422);

  private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^/{}]+}");

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
        || !OperatorAuditPaths.audits(path)
        || auth == null
        || auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
      return;
    }
    try {
      store.append(line(request.getMethod(), path, mapped(request), auth, response.getStatus()));
    } catch (RuntimeException failed) {
      logger.warn(
          "The operator audit line of {} {} could not be written",
          request.getMethod(),
          path,
          failed);
    }
  }

  /**
   * The mapping that served the request, when one did: its path template and the value of its last
   * variable. A template names every resource, ids and names alike ({@code /kinds/{kindId}}).
   */
  private static Mapped mapped(HttpServletRequest request) {
    if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            instanceof String template)
        || template.indexOf('{') < 0) {
      return null;
    }
    String target = null;
    if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
        instanceof Map<?, ?> variables) {
      for (Object value : variables.values()) {
        target = String.valueOf(value);
      }
    }
    return new Mapped(TEMPLATE_VARIABLE.matcher(template).replaceAll("{id}"), target);
  }

  /** A request's mapping: the template, every variable written {@code {id}}, and its target. */
  private record Mapped(String pattern, String target) {}

  private static OperatorAuditStore.Line line(
      String method, String path, Mapped mapped, Authentication auth, int status) {
    String outcome = status < 400 ? "ok" : REFUSALS.contains(status) ? "refused" : "failed";
    if (mapped != null) {
      return actor(auth, method + " " + mapped.pattern(), mapped.target(), outcome);
    }
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
    return actor(auth, method + " " + pattern, target, outcome);
  }

  private static OperatorAuditStore.Line actor(
      Authentication auth, String action, String target, String outcome) {
    // This service's principal is the hydrated User record: its id and e-mail, never its toString
    // (preferences, tiers).
    String actorId = auth.getName();
    String label = auth.getName();
    if (auth.getPrincipal() instanceof User user) {
      actorId = user.id().toString();
      label = user.email();
    } else if (auth instanceof JwtAuthenticationToken jwt) {
      label = claim(jwt, "email", auth.getName());
    }
    return new OperatorAuditStore.Line(
        actorId, label, action, target, MDC.get("requestId"), outcome);
  }

  private static String claim(JwtAuthenticationToken jwt, String name, String fallback) {
    String value = jwt.getToken().getClaimAsString(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
