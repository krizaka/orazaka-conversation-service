package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import com.krizaka.users.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Notes, before a voice turn is taken, that its conversation holds a regulated pack's turns
 * (orazaka#301).
 *
 * <p>The memory a turn is written to is keyed by a conversation id the client chose and has no
 * owner. This is the one place that knows, for a turn under an installation, <i>whose</i> it is and
 * <i>which pack</i> governs it, so the erase and the 30-day retention can reach it. A pack that
 * handles crisis content (it declares crisis terms - REGULATED, ADR-055) is the one that does; an
 * ordinary turn, or an ordinary pack, is not noted and nothing about it changes.
 *
 * <p>An interceptor in its own package rather than a line in the controller, because this is a
 * concern of the right to erasure and not of the voice turn; the turn itself is unchanged. It never
 * refuses a request: if the installation cannot be read (404, 451, the studio service down) the
 * turn's own handling refuses it, and nothing is noted for a turn that does not run.
 */
@Component
public class ProtectedTurnRecorder implements HandlerInterceptor {

  private static final Logger logger = LoggerFactory.getLogger(ProtectedTurnRecorder.class);

  private final TurnDeclarationClient declarations;
  private final ProtectedConversationStore conversations;

  ProtectedTurnRecorder(
      TurnDeclarationClient declarations, ProtectedConversationStore conversations) {
    this.declarations = Objects.requireNonNull(declarations, "TurnDeclarationClient required");
    this.conversations = Objects.requireNonNull(conversations, "service required");
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String installation = request.getParameter("installationId");
    String conversationId = request.getParameter("conversationId");
    if (installation == null || installation.isBlank() || conversationId == null) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
      return true;
    }
    try {
      String actorId = user.id().toString();
      Optional<TurnDeclarations> declared =
          declarations.forInstallation(UUID.fromString(installation), actorId);
      if (declared.isPresent() && !declared.get().crisisTerms().isEmpty()) {
        conversations.record(conversationId, actorId, installation, declared.get().packKey());
      }
    } catch (RuntimeException e) {
      // Not this interceptor's refusal to give: the turn itself judges the installation.
      logger.debug("Turn not noted as protected: {}", e.toString());
    }
    return true;
  }
}
