package com.orazaka.conversationservice.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.krizaka.users.domain.port.UserDirectoryClient;
import com.orazaka.core.application.engine.GraphEngine;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the filter wiring without any infrastructure: the custom filters are exposed as named
 * {@code Filter} beans (consumed by {@code SecurityConfig} via qualifiers) with container
 * auto-registration disabled, and the rate-limit filter is conditional.
 */
class FilterConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(FilterConfig.class)
          .withBean(GraphEngine.class, () -> mock(GraphEngine.class));

  @Test
  void operationGraphFilter_isAlwaysWiredAsFilterWithRegistrationDisabled() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasBean("operationGraphFilter");
          assertThat(ctx.getBean("operationGraphFilter")).isInstanceOf(Filter.class);
          assertThat(ctx).hasBean("operationGraphFilterRegistration");
        });
  }

  @Test
  void rateLimitFilter_isAbsentByDefault() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean("rateLimitFilter"));
  }

  @Test
  void rateLimitFilter_isWiredWhenEnabled() {
    runner
        .withPropertyValues("orazaka.identity.rate-limit.enabled=true")
        .withBean(ProxyManager.class, () -> mock(ProxyManager.class))
        .withBean(UserDirectoryClient.class, () -> mock(UserDirectoryClient.class))
        .run(
            ctx -> {
              assertThat(ctx).hasBean("rateLimitFilter");
              assertThat(ctx.getBean("rateLimitFilter")).isInstanceOf(Filter.class);
              assertThat(ctx).hasBean("rateLimitFilterRegistration");
            });
  }
}
