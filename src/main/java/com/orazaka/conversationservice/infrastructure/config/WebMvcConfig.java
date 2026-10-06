package com.orazaka.conversationservice.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration: async timeouts and media MIME mappings.
 *
 * <p>It used to also publish the upload tree through a {@code ResourceHandlerRegistry}. That is
 * gone: a static handler can answer "is this caller authenticated" but never "does this caller own
 * this file", so every tenant's media was one path segment away from every other. Assets are served
 * by {@code AssetController}, which resolves the owner from the job record before opening a stream.
 */
@Configuration
class WebMvcConfig implements WebMvcConfigurer {

  private final long asyncTimeoutMs;

  WebMvcConfig(@Value("${spring.mvc.async.request-timeout}") long asyncTimeoutMs) {
    this.asyncTimeoutMs = asyncTimeoutMs;
  }

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    configurer.setDefaultTimeout(asyncTimeoutMs);
  }

  @Bean
  public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>
      webServerFactoryCustomizer() {
    return factory -> {
      MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
      mappings.add("mp4", "video/mp4");
      mappings.add("png", "image/png");
      factory.setMimeMappings(mappings);
      if (factory instanceof TomcatServletWebServerFactory tomcatFactory) {
        tomcatFactory.addConnectorCustomizers(
            connector -> connector.setAsyncTimeout(asyncTimeoutMs));
      }
    };
  }
}
