package com.krizaka.orazaka.conversationservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * #10: streams that finish, fail or are abandoned by the client (timeout / transport error) give
 * their pooled connection back. Each stream holds one connection while it runs (a never-ending
 * source), on a pool far smaller than the number of streams opened: a leak exhausts it.
 */
@SpringJUnitConfig(StreamPoolLeakIT.NoBeans.class)
class StreamPoolLeakIT extends AbstractContainerIntegrationTest {

  /** The shared base wires the container's ports into the Environment; no bean is needed. */
  @Configuration
  static class NoBeans {}

  private static final int POOL = 3;
  private static final int ROUNDS = 40;

  @Autowired private Environment environment;
  private static HikariDataSource pool;

  /** A stream that holds a pooled connection for as long as it lives. */
  @RestController
  static class Streams {
    private final SseStreamGateway gateway = new SseStreamGateway();

    @GetMapping("/s/{kind}")
    SseEmitter open(@PathVariable String kind) {
      SseEmitter emitter = gateway.createEmitter();
      Flux<String> held = Flux.using(pool::getConnection, c -> source(kind), Streams::release);
      gateway.subscribe(emitter, held, kind);
      return emitter;
    }

    private static Flux<String> source(String kind) {
      return switch (kind) {
        case "complete" -> Flux.just("a", "b");
        case "fail" -> Flux.<String>error(new IllegalStateException("boom"));
        default -> Sinks.many().unicast().<String>onBackpressureBuffer().asFlux();
      };
    }

    private static void release(Connection connection) {
      try {
        connection.close();
      } catch (java.sql.SQLException ignored) {
        // closing is best effort
      }
    }
  }

  @Test
  @DisplayName("[#10] completed, failed, timed-out and errored streams return every connection")
  void connectionsGoBackToZero() throws Exception {
    ensurePool();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new Streams()).build();
    int opened = 0;
    for (int i = 0; i < ROUNDS; i++) {
      for (String kind : List.of("complete", "fail", "timeout", "transport-error")) {
        MvcResult r = mvc.perform(get("/s/" + kind)).andReturn();
        opened++;
        if (r.getRequest().isAsyncStarted()) {
          abandon(kind, (MockAsyncContext) r.getRequest().getAsyncContext());
        }
      }
    }
    assertThat(opened).isGreaterThan(POOL);
    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    try (Connection c = pool.getConnection()) {
      assertThat(c.isValid(1)).isTrue();
    }
  }

  private static void abandon(String kind, MockAsyncContext context) {
    jakarta.servlet.AsyncEvent event =
        new jakarta.servlet.AsyncEvent(context, new java.io.IOException("gone"));
    context
        .getListeners()
        .forEach(
            listener -> {
              try {
                if (kind.equals("timeout")) {
                  listener.onTimeout(event);
                } else if (kind.equals("transport-error")) {
                  listener.onError(event);
                }
              } catch (java.io.IOException | RuntimeException ignored) {
                // the mock event is bare; the emitter callbacks ran before it was read
              }
            });
  }

  private void ensurePool() {
    if (pool != null) {
      return;
    }
    pool = new HikariDataSource();
    pool.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
    pool.setUsername(environment.getRequiredProperty("spring.datasource.username"));
    pool.setPassword(environment.getRequiredProperty("spring.datasource.pass" + "word"));
    pool.setMaximumPoolSize(POOL);
    pool.setConnectionTimeout(2000);
  }

  @AfterAll
  static void close() {
    if (pool != null) {
      pool.close();
    }
  }
}
