package com.krizaka.orazaka.conversationservice.infrastructure.config;

import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.ProtectedTurnRecorder;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link ProtectedTurnRecorder} in front of the voice turn endpoint only. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProtectedConversationProperties.class)
class ProtectedConversationWebConfig implements WebMvcConfigurer {

  private final ProtectedTurnRecorder recorder;

  ProtectedConversationWebConfig(ProtectedTurnRecorder recorder) {
    this.recorder = Objects.requireNonNull(recorder, "ProtectedTurnRecorder required");
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(recorder).addPathPatterns("/api/v1/voice/turns");
  }
}
