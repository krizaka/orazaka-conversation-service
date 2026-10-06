package com.orazaka.conversationservice.infrastructure.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Local HS256 decoder over the shared identity secret (session validation stays hop-free). */
@Configuration
@EnableConfigurationProperties(SessionJwtProperties.class)
class SessionJwtConfig {

  @Bean
  JwtDecoder identityJwtDecoder(SessionJwtProperties properties) {
    return NimbusJwtDecoder.withSecretKey(
            new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }
}
