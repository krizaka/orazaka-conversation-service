package com.krizaka.orazaka.conversationservice.application.service;

import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.core.domain.model.Authority;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.model.UserProfile;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Application service that resolves the orchestration {@link Context} for an authenticated user,
 * enriched with their profile preferences.
 *
 * <p>Injects the {@link UserDirectoryClient} once here, so call sites (controllers, listeners)
 * depend only on this service instead of wiring the provider themselves [ERR-127].
 */
@Service
public class ContextService {

  /** The typed onboarding profile: fields identity validated, carried as the platform's. */
  static final String USER_PROFILE_KEY = Context.PLATFORM_NAMESPACE + "user.profile";

  /** The onboarding answers that form the assistant profile, kept out of the free preferences. */
  private static final Set<String> PROFILE_ATTRIBUTES =
      Set.of("voiceModel", "primaryIndustry", "aiBehavior");

  private static final String DEFAULT_VOICE_MODEL = "alloy";
  private static final String DEFAULT_PRIMARY_INDUSTRY = "tech";

  /**
   * The plan entitlement that puts a turn in no-storage mode ({@code retention: none}): the engine
   * keeps counters and metadata, never the prompt, the completion or a transcript. A plan is a row
   * (ADR-031), so enabling it for a product is data, never a deploy.
   */
  static final String RETENTION_NONE_ENTITLEMENT = "retention.none";

  private final UserDirectoryClient userDirectoryService;
  private final EntitlementProvider entitlements;

  /** A context with no plan to read: every turn keeps the engine's default retention. */
  public ContextService(UserDirectoryClient userDirectoryService) {
    this(userDirectoryService, null);
  }

  /**
   * @param userDirectoryService the profile source
   * @param entitlements the actor's plan, read to declare the no-storage mode; {@code null} keeps
   *     the default for everyone
   */
  @Autowired
  public ContextService(
      UserDirectoryClient userDirectoryService, EntitlementProvider entitlements) {
    this.userDirectoryService =
        Objects.requireNonNull(userDirectoryService, "UserDirectoryClient cannot be null");
    this.entitlements = entitlements;
  }

  /**
   * Resolves the orchestration context for a user.
   *
   * @param user The authenticated domain user.
   * @param conversationId Optional conversation ID (may be {@code null}).
   * @return Fully assembled {@link Context}.
   */
  public Context resolve(User user, String conversationId) {
    UserProfile profile = userDirectoryService.getProfile(user.id().toString());
    return buildContext(
        user, conversationId, UUID.randomUUID(), profile, retentionNone(user.id().toString()));
  }

  /**
   * Whether the actor's plan puts their turns in no-storage mode. A plan that could not be read
   * says nothing about the actor, so it keeps the default rather than guessing.
   */
  private boolean retentionNone(String actorId) {
    if (entitlements == null) {
      return false;
    }
    EntitlementSnapshot plan = entitlements.forActor(actorId);
    return plan.resolved() && plan.allows(RETENTION_NONE_ENTITLEMENT);
  }

  /** Assembles the {@link Context}, merging profile preferences when a profile is available. */
  private static Context buildContext(
      User user,
      String conversationId,
      UUID sessionId,
      UserProfile profile,
      boolean retentionNone) {
    Set<Authority> authorities =
        user.authorities().stream().map(Authority::new).collect(Collectors.toSet());

    // What the user wrote goes under their own namespace, and what this service knows under the
    // platform's: a stored preference named userId or orazaka.metering.deferred used to arrive here
    // as that key, and moved or removed the credit hold (ADR-064).
    Map<String, Object> preferences = new HashMap<>(Context.userPreferences(user.preferences()));
    // Request-bound session, propagated for cross-service traceability.
    preferences.put(Context.SESSION_ID_KEY, sessionId.toString());
    if (retentionNone) {
      // The platform alone declares it (never a user preference): the memory interceptor neither
      // reads nor writes this conversation, and nothing of the turn's content outlives it.
      preferences.put(Context.RETENTION_KEY, Context.RETENTION_NONE);
    }

    if (profile != null) {
      // The users service stores Orazaka's onboarding answers as attributes it never interprets;
      // which ones form the assistant profile, and their defaults, are Orazaka's to decide.
      Map<String, Object> attributes = new HashMap<>(profile.attributes());
      Map<String, Object> profileContext = new HashMap<>();
      profileContext.put("theme", profile.theme());
      profileContext.put("voiceModel", attributes.getOrDefault("voiceModel", DEFAULT_VOICE_MODEL));
      profileContext.put(
          "primaryIndustry", attributes.getOrDefault("primaryIndustry", DEFAULT_PRIMARY_INDUSTRY));
      Object aiBehavior = attributes.get("aiBehavior");
      if (aiBehavior != null) {
        profileContext.put("aiBehavior", aiBehavior);
      }
      preferences.put(USER_PROFILE_KEY, Map.copyOf(profileContext));
      attributes.keySet().removeAll(PROFILE_ATTRIBUTES);
      preferences.putAll(Context.userPreferences(attributes));
    }

    return new Context(
        user.id().toString(),
        conversationId != null ? conversationId : "none",
        Map.copyOf(preferences),
        authorities);
  }
}
