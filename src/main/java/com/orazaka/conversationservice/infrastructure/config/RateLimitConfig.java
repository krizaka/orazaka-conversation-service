package com.orazaka.conversationservice.infrastructure.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditional configuration class for the dynamic rate limiting engine.
 *
 * <p>Only instantiates client connection pools to Redis when the master toggle {@code
 * orazaka.identity.rate-limit.enabled} is set to {@code true}.
 */
@Configuration
@ConditionalOnProperty(name = "orazaka.identity.rate-limit.enabled", havingValue = "true")
public class RateLimitConfig {

  private final String redisUrl;

  /**
   * Constructs the rate limiting configuration class with injected environment properties.
   *
   * @param redisUrl The Spring Boot Redis configuration URL.
   */
  public RateLimitConfig(@Value("${spring.data.redis.url}") String redisUrl) {
    this.redisUrl = redisUrl;
  }

  /**
   * Instantiates the standalone Lettuce {@link RedisClient} bean.
   *
   * @return The configured and ready {@link RedisClient} instance.
   * @see io.lettuce.core.RedisClient
   */
  @Bean(destroyMethod = "shutdown")
  public RedisClient redisClient() {
    return RedisClient.create(RedisURI.create(redisUrl));
  }

  /**
   * Opens a stateful connection to the Redis server using a UTF-8 String codec for keys and a raw
   * byte array codec for values.
   *
   * @param redisClient The active Lettuce Redis client.
   * @return A thread-safe, stateful connection bean to Redis.
   * @see io.lettuce.core.api.StatefulRedisConnection
   */
  @Bean(destroyMethod = "close")
  public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient redisClient) {
    return redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
  }

  /**
   * Builds the Bucket4j Lettuce-based {@link ProxyManager} to allow atomic remote bucket
   * evaluation.
   *
   * @param connection The stateful connection to Redis.
   * @return The Lettuce-backed proxy manager bean.
   * @see io.github.bucket4j.distributed.proxy.ProxyManager
   */
  @Bean
  @ConditionalOnProperty(name = "orazaka.identity.rate-limit.enabled", havingValue = "true")
  public ProxyManager<String> proxyManager(StatefulRedisConnection<String, byte[]> connection) {
    return Bucket4jLettuce.casBasedBuilder(connection).build();
  }
}
