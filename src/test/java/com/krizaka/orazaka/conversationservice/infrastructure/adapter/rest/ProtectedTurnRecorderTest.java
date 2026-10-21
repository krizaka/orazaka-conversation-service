package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import com.krizaka.orazaka.studio.domain.model.PackScopeGuard;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import com.krizaka.users.domain.model.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ProtectedTurnRecorderTest {

  private static final UUID ACTOR = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");
  private static final UUID INSTALLATION = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");

  private final TurnDeclarationClient declarations = mock(TurnDeclarationClient.class);
  private final ProtectedConversationStore conversations = mock(ProtectedConversationStore.class);
  private final ProtectedTurnRecorder recorder =
      new ProtectedTurnRecorder(declarations, conversations);

  private static TurnDeclarations declared(List<String> crisisTerms) {
    return new TurnDeclarations(
        INSTALLATION,
        "session-summary",
        "bien-etre",
        "CA-QC",
        new PackScopeGuard(List.of("posologie"), "Je ne parle pas de posologie."),
        crisisTerms,
        crisisTerms.isEmpty() ? null : "Je m'arrête là.",
        crisisTerms.isEmpty()
            ? null
            : new CrisisResource(
                "CA-QC",
                "Prévention du suicide",
                "1 866 APPELLE",
                "24/7",
                "https://www.quebec.ca/",
                LocalDate.of(2026, 10, 20)),
        null);
  }

  private MockHttpServletRequest turn(String installation) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/voice/turns");
    request.setParameter("conversationId", "conv-1");
    if (installation != null) {
      request.setParameter("installationId", installation);
    }
    return request;
  }

  private void signedInAsEric() {
    User user = mock(User.class);
    when(user.id()).thenReturn(ACTOR);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(user, null));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("a turn under a regulated installation notes whose conversation it is held in")
  void notesARegulatedTurn() {
    signedInAsEric();
    when(declarations.forInstallation(INSTALLATION, ACTOR.toString()))
        .thenReturn(Optional.of(declared(List.of("en finir"))));

    boolean go =
        recorder.preHandle(turn(INSTALLATION.toString()), new MockHttpServletResponse(), null);

    assertThat(go).as("never refuses: the turn judges itself").isTrue();
    verify(conversations).record("conv-1", ACTOR.toString(), INSTALLATION.toString(), "bien-etre");
  }

  @Test
  @DisplayName(
      "an ordinary pack (no crisis handling) is not noted, nor a turn with no installation")
  void notesNothingElse() {
    signedInAsEric();
    when(declarations.forInstallation(INSTALLATION, ACTOR.toString()))
        .thenReturn(Optional.of(declared(List.of())));

    recorder.preHandle(turn(INSTALLATION.toString()), new MockHttpServletResponse(), null);
    recorder.preHandle(turn(null), new MockHttpServletResponse(), null);

    verify(conversations, never()).record(any(), any(), any(), any());
  }

  @Test
  @DisplayName("an installation that cannot be read is the turn's refusal to give, not this one's")
  void neverRefuses() {
    signedInAsEric();
    when(declarations.forInstallation(any(), any())).thenThrow(new IllegalStateException("down"));

    assertThat(
            recorder.preHandle(turn(INSTALLATION.toString()), new MockHttpServletResponse(), null))
        .isTrue();
    verify(conversations, never()).record(any(), any(), any(), any());
  }

  @Test
  @DisplayName("an anonymous request is not noted")
  void anonymousIsIgnored() {
    recorder.preHandle(turn(INSTALLATION.toString()), new MockHttpServletResponse(), null);

    verify(conversations, never()).record(any(), any(), any(), any());
  }
}
