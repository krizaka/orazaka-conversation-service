package com.krizaka.orazaka.conversationservice.infrastructure.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The HTTP door of the external checks, mapped from {@code orazaka.checks} (orazaka-ai-engine#105).
 *
 * @param requiredRoles the roles any of which may use the door (default: administrators and service
 *     accounts); a caller with none is refused 403, as ADR-091 asks of every tool
 * @param ratePerMinute checks one actor may ask for in a minute (default 30)
 * @param providerTimeout how long a provider may take (default 10 seconds)
 * @param mockProvider whether the mock connector can be a provider row: for CI and the demo only,
 *     off by default, so an unconfigured platform answers {@code NOT_CONFIGURED} and never a
 *     fixture
 */
@ConfigurationProperties(prefix = "orazaka.checks")
public record ChecksDoorProperties(
    Set<String> requiredRoles, int ratePerMinute, Duration providerTimeout, boolean mockProvider) {

  /** Fills the defaults of what a deployment left out. */
  public ChecksDoorProperties {
    requiredRoles =
        requiredRoles == null || requiredRoles.isEmpty()
            ? Set.of("ROLE_ADMIN", "ROLE_SERVICE")
            : Set.copyOf(requiredRoles);
    ratePerMinute = ratePerMinute <= 0 ? 30 : ratePerMinute;
    providerTimeout = providerTimeout == null ? Duration.ofSeconds(10) : providerTimeout;
  }
}
