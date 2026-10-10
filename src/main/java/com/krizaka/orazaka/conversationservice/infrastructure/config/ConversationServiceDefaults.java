package com.krizaka.orazaka.conversationservice.infrastructure.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * The operating defaults of the conversation service, as the <b>lowest-priority</b> property source
 * — the pattern krizaka-web and krizaka-observability use for theirs.
 *
 * <p>{@code application.yml} names what a deployment decides (where the database, the broker and
 * the other services are, the secrets, the browser origins); {@value #LOCATION} holds how the
 * service runs well (virtual threads, pool and listener tuning, upload limits, the synchronous
 * capability endpoints). Package-private like every class of this pack ([ERR-110]); Spring Boot's
 * factories loader instantiates it reflectively. {@code application.yml}, the environment and the
 * command line override every value there.
 */
class ConversationServiceDefaults implements EnvironmentPostProcessor {

  /** Where the defaults live on the classpath. */
  static final String LOCATION = "META-INF/orazaka/conversation-service-defaults.yml";

  /** The name of the property source this post-processor adds. */
  static final String PROPERTY_SOURCE_NAME = "conversationServiceDefaults";

  /** Creates the post-processor; Spring Boot instantiates it from {@code spring.factories}. */
  ConversationServiceDefaults() {}

  /**
   * Adds the defaults below every other property source.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
      return;
    }
    Resource defaults =
        new ClassPathResource(LOCATION, ConversationServiceDefaults.class.getClassLoader());
    try {
      new YamlPropertySourceLoader()
          .load(PROPERTY_SOURCE_NAME, defaults)
          .forEach(environment.getPropertySources()::addLast);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read " + LOCATION, unreadable);
    }
  }
}
