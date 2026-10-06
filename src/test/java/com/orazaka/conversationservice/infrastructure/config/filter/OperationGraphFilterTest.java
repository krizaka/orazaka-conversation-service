package com.orazaka.conversationservice.infrastructure.config.filter;

import static com.orazaka.test.TestConstants.*;
import static org.mockito.Mockito.*;

import com.orazaka.conversationservice.infrastructure.config.CapabilityEndpointProperties;
import com.orazaka.core.application.engine.GraphEngine;
import com.orazaka.core.domain.model.NodeState.Active;
import com.orazaka.core.domain.model.NodeState.Locked;
import com.orazaka.core.domain.model.OperationGraph;
import com.orazaka.core.domain.model.OperationNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests verifying OperationGraphFilter requests blocking. */
class OperationGraphFilterTest {
  private static final java.time.Clock FIXED_CLOCK =
      java.time.Clock.fixed(
          java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

  private GraphEngine graphEngine;
  private OperationGraphFilter filter;

  @BeforeEach
  void setUp() {
    graphEngine = mock(GraphEngine.class);
    // The pairing the filter now reads instead of a capability's uri_path column (ADR-069 §5).
    filter =
        new OperationGraphFilter(
            graphEngine,
            new CapabilityEndpointProperties(
                java.util.Map.of("/api/v1/chat", "orazaka.core.chat.completion")));
  }

  @Test
  void testFilterAllowsActiveOperation() throws Exception {
    OperationGraph graph =
        new OperationGraph(
            List.of(
                new OperationNode(
                    "orazaka.core.chat.completion", "CONTEXT_MENU_PLUS", new Active())));
    when(graphEngine.compileGraph()).thenReturn(graph);

    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain filterChain = mock(FilterChain.class);

    when(request.getRequestURI()).thenReturn("/api/v1/chat/stream");
    when(request.getMethod()).thenReturn(METHOD_POST);

    filter.doFilterInternal(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verify(response, never()).setStatus(anyInt());
  }

  @Test
  void testFilterBlocksLockedOperation() throws Exception {
    OperationGraph graph =
        new OperationGraph(
            List.of(
                new OperationNode(
                    "orazaka.core.chat.completion",
                    "CONTEXT_MENU_PLUS",
                    new Locked("Maintenance", LocalDateTime.now(FIXED_CLOCK)))));
    when(graphEngine.compileGraph()).thenReturn(graph);

    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain filterChain = mock(FilterChain.class);

    when(request.getRequestURI()).thenReturn("/api/v1/chat/stream");
    when(request.getMethod()).thenReturn(METHOD_POST);

    StringWriter stringWriter = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

    filter.doFilterInternal(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    verify(filterChain, never()).doFilter(any(), any());
  }
}
