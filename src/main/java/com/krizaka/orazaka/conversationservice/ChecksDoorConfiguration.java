package com.krizaka.orazaka.conversationservice;

import com.krizaka.orazaka.conversationservice.application.service.CheckRateLimitService;
import com.krizaka.orazaka.conversationservice.application.service.ProductCheckService;
import com.krizaka.orazaka.conversationservice.domain.port.ProductCheckAudit;
import com.krizaka.orazaka.conversationservice.infrastructure.config.ChecksDoorProperties;
import com.krizaka.orazaka.core.application.checks.CheckRouter;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckHttp;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckKeyStore;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckMeter;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckProviderCatalog;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckSubjectPepper;
import com.krizaka.orazaka.core.domain.ports.outbound.ExternalCheckConnector;
import com.krizaka.orazaka.core.infrastructure.checks.HttpCheckConnector;
import com.krizaka.orazaka.core.infrastructure.checks.JdkCheckHttp;
import com.krizaka.orazaka.core.infrastructure.checks.MockConnector;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the engine's one door to the HTTP door for products (orazaka-ai-engine#105). The provider
 * rows, the sealed keys and the sealed pepper are the engine's own beans; the handlers are the
 * adapters a row's {@code handler-key} can name. Without billing the meter refuses every call, so a
 * platform that cannot charge asks no provider.
 */
@Configuration
@EnableConfigurationProperties(ChecksDoorProperties.class)
public class ChecksDoorConfiguration {

  /**
   * @param properties the door's settings
   * @return the adapters a provider row can name
   */
  @Bean
  public Map<String, ExternalCheckConnector> checkHandlers(ChecksDoorProperties properties) {
    CheckHttp http = new JdkCheckHttp();
    Map<String, ExternalCheckConnector> handlers = new LinkedHashMap<>();
    for (ExternalCheckConnector connector :
        List.of(
            HttpCheckConnector.soquij(http, properties.providerTimeout()),
            HttpCheckConnector.trustii(http, properties.providerTimeout()),
            HttpCheckConnector.certn(http, properties.providerTimeout()))) {
      handlers.put(connector.provider(), connector);
    }
    if (properties.mockProvider()) {
      MockConnector mock = new MockConnector();
      handlers.put(mock.provider(), mock);
    }
    return handlers;
  }

  /**
   * @param catalog the provider rows (admin settings)
   * @param handlers the adapters
   * @param keys the sealed provider keys
   * @param meter the billing meter, when billing is on
   * @param pepper the sealed subject pepper
   * @param audit the checks' audit
   * @param properties the door's settings
   * @return the service behind {@code /api/v1/checks}
   */
  @Bean
  public ProductCheckService productCheckService(
      CheckProviderCatalog catalog,
      Map<String, ExternalCheckConnector> checkHandlers,
      CheckKeyStore keys,
      ObjectProvider<CheckMeter> meter,
      CheckSubjectPepper pepper,
      ProductCheckAudit audit,
      ChecksDoorProperties properties) {
    Clock clock = Clock.systemUTC();
    CheckMeter closed =
        (runId, actorId, provider, purpose) -> {
          throw new CheckMeter.BudgetExhaustedException("billing is off: checks stay off");
        };
    return new ProductCheckService(
        new CheckRouter(catalog, checkHandlers),
        keys,
        meter.getIfAvailable(() -> closed),
        pepper,
        audit,
        new CheckRateLimitService(properties.ratePerMinute(), clock),
        properties.requiredRoles(),
        clock);
  }
}
