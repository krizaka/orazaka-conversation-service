package com.orazaka.conversationservice.infrastructure.config;

import static org.mockito.Mockito.*;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

@ExtendWith(MockitoExtension.class)
class WebMvcConfigTest {

  private WebMvcConfig webMvcConfig;

  @BeforeEach
  void setUp() {
    webMvcConfig = new WebMvcConfig(30000);
  }

  @Test
  void configureAsyncSupport_setsDefaultTimeout() {
    AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);
    webMvcConfig.configureAsyncSupport(configurer);
    verify(configurer).setDefaultTimeout(30000);
  }

  @Test
  void webServerFactoryCustomizer_configuresMimeTypes() {
    var customizer = webMvcConfig.webServerFactoryCustomizer();
    ConfigurableServletWebServerFactory factory = mock(ConfigurableServletWebServerFactory.class);

    customizer.customize(factory);

    verify(factory).setMimeMappings(any());
  }

  @Test
  void webServerFactoryCustomizer_withTomcatFactory_configuresAsyncTimeout() {
    var customizer = webMvcConfig.webServerFactoryCustomizer();
    TomcatServletWebServerFactory tomcatFactory = mock(TomcatServletWebServerFactory.class);
    Connector connector = mock(Connector.class);

    customizer.customize(tomcatFactory);

    // Verify connector customizers are added
    ArgumentCaptor<TomcatConnectorCustomizer> captor =
        ArgumentCaptor.forClass(TomcatConnectorCustomizer.class);
    verify(tomcatFactory).addConnectorCustomizers(captor.capture());

    // Execute the customizer and verify timeout is set
    captor.getValue().customize(connector);
    verify(connector).setAsyncTimeout(30000);
  }
}
