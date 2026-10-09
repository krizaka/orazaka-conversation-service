package com.krizaka.orazaka.conversationservice.infrastructure.config.filter;

import com.krizaka.orazaka.conversationservice.infrastructure.config.CapabilityEndpointProperties;
import com.krizaka.orazaka.core.application.engine.GraphEngine;
import com.krizaka.orazaka.core.domain.model.NodeState.Active;
import com.krizaka.orazaka.core.domain.model.NodeState.Invisible;
import com.krizaka.orazaka.core.domain.model.NodeState.Locked;
import com.krizaka.orazaka.core.domain.model.OperationGraph;
import com.krizaka.orazaka.core.domain.model.OperationNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request for a synchronous capability whose engine is not available.
 *
 * <p>It matched the request against every capability's {@code uri_path} until ADR-069 §5. Those
 * columns are gone and most of what they named had already been deleted with door 1, so the match
 * was dead for every row but one: {@code orazaka.core.chat.completion}, which is genuinely served
 * over HTTP by this service and stays off the broker (AGENTS.md §6). The pairing of a path this
 * service owns with the capability behind it is now declared in this service's own configuration
 * ({@link CapabilityEndpointProperties}) rather than read from a registry shared by every context.
 */
class OperationGraphFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(OperationGraphFilter.class);

  private final GraphEngine graphEngine;
  private final CapabilityEndpointProperties endpoints;

  /**
   * Constructs the filter.
   *
   * @param graphEngine The graph engine evaluating capability states.
   * @param endpoints the synchronous endpoints this service serves, and what runs them
   */
  public OperationGraphFilter(GraphEngine graphEngine, CapabilityEndpointProperties endpoints) {
    this.graphEngine = Objects.requireNonNull(graphEngine, "GraphEngine cannot be null");
    this.endpoints =
        Objects.requireNonNull(endpoints, "CapabilityEndpointProperties cannot be null");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String path = request.getRequestURI();

    Optional<String> gatedCapability =
        endpoints.paths().entrySet().stream()
            .filter(endpoint -> matches(path, endpoint.getKey()))
            .map(Map.Entry::getValue)
            .findFirst();
    if (gatedCapability.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    OperationGraph graph = graphEngine.compileGraph();
    Optional<OperationNode> matchingNode =
        graph.nodes().stream().filter(node -> node.id().equals(gatedCapability.get())).findFirst();

    if (matchingNode.isPresent()) {
      OperationNode node = matchingNode.get();
      boolean allowed =
          switch (node.state()) {
            case Active active -> true;
            case Locked locked -> false;
            case Invisible invisible -> false;
          };

      if (!allowed) {
        LOGGER.warn(
            "Boundary rejection: REST capability '{}' state is {}",
            node.id(),
            node.state().getClass().getSimpleName());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response
            .getWriter()
            .write("{\"error\": \"Forbidden: Operation is currently unavailable\"}");
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  /** Whether this request is one of the declared synchronous endpoints. */
  private static boolean matches(String path, String declaredPrefix) {
    String cleanPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    String cleanPrefix =
        declaredPrefix.endsWith("/")
            ? declaredPrefix.substring(0, declaredPrefix.length() - 1)
            : declaredPrefix;
    // startsWith already covers the exact-match case (a string starts with itself).
    return cleanPath.startsWith(cleanPrefix);
  }
}
