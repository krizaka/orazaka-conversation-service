package com.orazaka.conversationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Orazaka Conversation Service (interactive host — ex-router BFF) application.
 *
 * <p>Configures and runs the Spring Boot web application, setting up base package scanning to
 * automatically discover router routes, controllers, and services under the {@code com.orazaka}
 * hierarchy.
 *
 * <p>The broad {@code com.orazaka} scan can otherwise pick up test-only
 * {@code @SpringBootConfiguration} bootstrap classes (e.g. {@code PersistenceTestApplication}) when
 * an IDE leaks a dependent module's test output onto the run classpath. Their
 * {@code @EnableJpaRepositories} then re-registers JPA repositories, failing startup with a {@code
 * BeanDefinitionOverrideException}. Such {@code *TestApplication} classes are never part of the
 * production context, so they are excluded from the scan.
 *
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    basePackages = "com.orazaka",
    excludeFilters = {
      // Preserve the two filters @SpringBootApplication applies implicitly…
      @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.CUSTOM,
          classes = AutoConfigurationExcludeFilter.class),
      // …then add ours: never scan test-only *TestApplication bootstrap classes.
      @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.orazaka\\..*TestApplication")
    })
@ConfigurationPropertiesScan("com.orazaka")
@EnableScheduling
public class ConversationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ConversationServiceApplication.class, args);
  }
}
