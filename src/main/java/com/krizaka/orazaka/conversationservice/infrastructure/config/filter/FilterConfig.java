package com.krizaka.orazaka.conversationservice.infrastructure.config.filter;

import com.krizaka.orazaka.conversationservice.infrastructure.config.CapabilityEndpointProperties;
import com.krizaka.orazaka.core.application.engine.GraphEngine;
import com.krizaka.users.domain.port.UserDirectoryClient;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the router's custom servlet filters as {@code Filter} beans.
 *
 * <p>The filter classes stay package-private in this package ([ERR-110]/ADR-009); exposing them as
 * qualified {@code jakarta.servlet.Filter} beans lets {@code SecurityConfig} (in the parent {@code
 * config} package) add them to the security chain without referencing the concrete types. Each
 * filter's automatic servlet-container registration is disabled — they run only inside the Spring
 * Security chain, where {@code SecurityConfig} places them after the bearer-token filter.
 */
@Configuration
@EnableConfigurationProperties(CapabilityEndpointProperties.class)
class FilterConfig {

  /** Capability-availability filter, exposed for the security chain. */
  @Bean
  Filter operationGraphFilter(
      GraphEngine graphEngine, CapabilityEndpointProperties capabilityEndpoints) {
    return new OperationGraphFilter(graphEngine, capabilityEndpoints);
  }

  /** Disables container auto-registration of the operation-graph filter. */
  @Bean
  FilterRegistrationBean<Filter> operationGraphFilterRegistration(
      @Qualifier("operationGraphFilter") Filter filter) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * Distributed rate-limiting filter (only when {@code orazaka.identity.rate-limit.enabled=true});
   * uses the Lettuce proxy manager and DB-driven tiers.
   */
  @Bean
  @ConditionalOnProperty(name = "orazaka.identity.rate-limit.enabled", havingValue = "true")
  Filter rateLimitFilter(
      ProxyManager<String> proxyManager, UserDirectoryClient userDirectoryService) {
    return new RateLimitFilter(proxyManager, userDirectoryService);
  }

  /** Disables container auto-registration of the rate-limit filter (security chain only). */
  @Bean
  @ConditionalOnProperty(name = "orazaka.identity.rate-limit.enabled", havingValue = "true")
  FilterRegistrationBean<Filter> rateLimitFilterRegistration(
      @Qualifier("rateLimitFilter") Filter filter) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
