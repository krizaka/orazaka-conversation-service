package com.orazaka.conversationservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.port.CreditAuthorizationClient;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.model.UserProfile;
import com.krizaka.users.domain.port.UserDirectoryClient;
import com.orazaka.core.application.pipeline.DynamicPipelineExecutor;
import com.orazaka.core.application.pipeline.PipelineRegistry;
import com.orazaka.core.application.routing.SemanticRoutingEngine;
import com.orazaka.core.domain.model.Context;
import com.orazaka.core.domain.model.InterceptorConfig;
import com.orazaka.core.domain.model.PipelineConfig;
import com.orazaka.core.domain.model.PromptContext;
import com.orazaka.core.domain.model.RoutingMode;
import com.orazaka.core.infrastructure.config.CoreProperties;
import com.orazaka.core.infrastructure.config.SecurityProperties;
import com.orazaka.interceptor.governance.EntitlementInterceptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What a user can write about themselves cannot become what the platform says about them (ADR-064,
 * audit #23).
 *
 * <p>End to end through the production path a chat turn takes: {@link ContextService} builds the
 * context from the user record and the profile, {@link DynamicPipelineExecutor} merges it into the
 * metadata interceptors read, and {@link EntitlementInterceptor} takes the credit hold. The user
 * and the profile are the only hostile parties, and they carry exactly what {@code PUT
 * /api/v1/profile/preferences} could store: the engine's own key {@code userId} and the platform's
 * metering marker. Before ADR-064 the first moved the hold onto another actor's wallet, and the
 * second removed it.
 *
 * <p>A contract, not a rule. The defect is data flow — caller-controlled values reaching a map that
 * holds trusted keys — and no scanner of this repository follows a value from a stored preference
 * to a {@code putAll}. A bytecode rule would have to key on the calls around the merge and would
 * read the wrong span, as ADR-062's first quantity rule did.
 */
class UserPreferencesContractTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440064";
  private static final String VICTIM = "550e8400-e29b-41d4-a716-4466554400ff";

  @Test
  @DisplayName("a stored preference neither moves the credit hold nor removes it")
  void aStoredPreferenceCannotRedirectOrRemoveTheHold() {
    User hostile =
        new User(
            UUID.fromString(ACTOR),
            "mallory",
            "mallory@example.com",
            true,
            Set.of("ROLE_USER"),
            Map.of("userId", VICTIM, "orazaka.metering.deferred", true),
            null,
            "free");
    UserDirectoryClient directory = mock(UserDirectoryClient.class);
    when(directory.getProfile(ACTOR))
        .thenReturn(
            new UserProfile(
                ACTOR,
                "dark",
                attributes(
                    Map.of("conversationId", "not-mine", "orazaka.metering.deferred", "true"),
                    "alloy",
                    "tech",
                    "friendly")));
    EntitlementProvider entitlements = mock(EntitlementProvider.class);
    when(entitlements.forActor(any()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR,
                "pro",
                Map.of("capability.chat", "true"),
                500L,
                Instant.now().plusSeconds(60)));
    CreditAuthorizationClient credits = mock(CreditAuthorizationClient.class);
    when(credits.hold(any())).thenReturn(new CreditHoldResponse("hold-64", true, false, 2, 498, 1));

    Context context = new ContextService(directory).resolve(hostile, "conversation-64");
    PromptContext result =
        pipeline(
                new EntitlementInterceptor(
                    entitlements,
                    credits,
                    mock(com.krizaka.billing.domain.port.UnmeteredTurnRepository.class)))
            .process("bonjour", 0, context);

    ArgumentCaptor<CreditHoldCommand> hold = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(credits).hold(hold.capture());
    assertThat(hold.getValue().actorId()).as("the wallet charged").isEqualTo(ACTOR);
    assertThat(hold.getValue().correlationId()).as("the conversation").isEqualTo("conversation-64");
    assertThat(result.billingHoldId()).as("the hold the turn settles").isEqualTo("hold-64");
  }

  private static DynamicPipelineExecutor pipeline(EntitlementInterceptor gate) {
    PipelineRegistry registry = mock(PipelineRegistry.class);
    when(registry.getConfig(any()))
        .thenReturn(
            new PipelineConfig(
                "default",
                List.of(),
                List.of(
                    new InterceptorConfig("EntitlementInterceptor", "Entitlement", 4, true, "")),
                List.of()));
    CoreProperties properties =
        new CoreProperties(
            "ollama",
            new CoreProperties.RagConfig(false, null, 3),
            new CoreProperties.McpConfig(List.of()),
            new CoreProperties.OrchestrationConfig(
                true,
                null,
                null,
                new CoreProperties.RoutingConfig(RoutingMode.DETERMINISTIC, null)),
            null,
            null,
            null,
            null);
    return new DynamicPipelineExecutor(
        List.of(gate),
        registry,
        mock(SemanticRoutingEngine.class),
        new SecurityProperties(false),
        properties,
        new SimpleMeterRegistry(),
        event -> {});
  }

  /** Orazaka's onboarding answers, as the users service stores them: plain profile attributes. */
  private static Map<String, Object> attributes(
      Map<String, Object> others, String voiceModel, String primaryIndustry, String aiBehavior) {
    Map<String, Object> attributes = new java.util.HashMap<>(others);
    attributes.put("voiceModel", voiceModel);
    attributes.put("primaryIndustry", primaryIndustry);
    attributes.put("aiBehavior", aiBehavior);
    return attributes;
  }
}
