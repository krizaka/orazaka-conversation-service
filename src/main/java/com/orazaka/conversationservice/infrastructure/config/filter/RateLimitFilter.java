package com.orazaka.conversationservice.infrastructure.config.filter;

import com.orazaka.conversationservice.application.service.UserDirectoryService;
import com.orazaka.identity.domain.model.RateLimitInfo;
import com.orazaka.identity.domain.model.User;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP request rate-limiting filter using Bucket4j token-bucket algorithm.
 *
 * <p>Applies per-user (authenticated) or per-IP (anonymous) rate limiting based on configurable
 * tiers stored in the database. Tier configurations are cached in-memory for 10 seconds to minimize
 * database roundtrips.
 *
 * <p>Responds with HTTP 429 and JSON error body when the rate limit is exceeded. Sets {@code
 * X-RateLimit-Limit} and {@code X-RateLimit-Remaining} headers on all responses.
 *
 * @see RateLimitConfig
 */
class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger RATE_LIMIT_LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

  /**
   * Sentinel tier key used when neither the user nor the database resolves a tier. It never matches
   * a real row, so {@link #getTierConfig} falls back to its conservative bucket — the most
   * restrictive, correct behavior for an unresolvable tier.
   */
  private static final String UNRESOLVED_TIER = "__unresolved__";

  private final ProxyManager<String> proxyManager;
  private final UserDirectoryService userDirectoryService;
  private final ConcurrentHashMap<String, CachedTier> cache = new ConcurrentHashMap<>();

  /**
   * Constructs the rate limit filter.
   *
   * @param proxyManager Bucket4j proxy manager for distributed buckets.
   * @param userDirectoryService Provider for resolving rate limit tier configurations and the
   *     DB-flagged default tier.
   */
  public RateLimitFilter(
      ProxyManager<String> proxyManager, UserDirectoryService userDirectoryService) {
    this.proxyManager = proxyManager;
    this.userDirectoryService = userDirectoryService;
  }

  /**
   * Cached rate limit tier configuration with TTL expiry.
   *
   * @param capacity Maximum bucket capacity (tokens).
   * @param refillTokens Number of tokens refilled per interval.
   * @param refillSeconds Refill interval duration in seconds.
   * @param expiresAt Cache entry expiry timestamp (millis since epoch).
   */
  record CachedTier(int capacity, int refillTokens, int refillSeconds, long expiresAt) {}

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<User> authenticatedUser =
        Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .filter(auth -> auth.getPrincipal() instanceof User)
            .map(auth -> (User) auth.getPrincipal());

    String key =
        authenticatedUser.map(user -> user.id().toString()).orElseGet(request::getRemoteAddr);

    // Default tier is data (orazaka_rate_limits.is_default), not yaml config.
    String resolvedTier =
        authenticatedUser
            .map(User::rateLimitTier)
            .filter(tier -> !tier.isBlank())
            .or(userDirectoryService::getDefaultTierKey)
            .orElse(UNRESOLVED_TIER);

    CachedTier tierConfig = getTierConfig(resolvedTier);

    BucketConfiguration configuration =
        BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(tierConfig.capacity())
                    .refillIntervally(
                        tierConfig.refillTokens(), Duration.ofSeconds(tierConfig.refillSeconds()))
                    .build())
            .build();

    String bucketKey = key + ":" + resolvedTier;
    Bucket bucket = proxyManager.builder().build(bucketKey, () -> configuration);

    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      response.setHeader("X-RateLimit-Limit", String.valueOf(tierConfig.capacity()));
      response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(429);
      response.setHeader("X-RateLimit-Limit", String.valueOf(tierConfig.capacity()));
      response.setHeader("X-RateLimit-Remaining", "0");
      response.setHeader("Content-Type", "application/json");
      response.getWriter().write("{\"error\": \"Too Many Requests\"}");
    }
  }

  private CachedTier getTierConfig(String tierId) {
    long now = System.currentTimeMillis();
    CachedTier cached = cache.get(tierId);
    if (cached != null && cached.expiresAt() > now) {
      return cached;
    }

    try {
      Optional<RateLimitInfo> tierOpt = userDirectoryService.getRateLimit(tierId);
      if (tierOpt.isPresent()) {
        RateLimitInfo tier = tierOpt.get();
        CachedTier resolved =
            new CachedTier(tier.requestsPerMinute(), tier.requestsPerMinute(), 60, now + 10000);
        cache.put(tierId, resolved);
        return resolved;
      }
    } catch (DataAccessException e) {
      RATE_LIMIT_LOGGER.error(
          "Failed to fetch rate limit tier '{}' from database, using fallback config.", tierId, e);
    }

    return new CachedTier(10, 1, 1, now + 10000);
  }
}
