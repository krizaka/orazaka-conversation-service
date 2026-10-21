package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore.Erased;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine surface of the right to erasure for conversations (orazaka#301): the Studio service,
 * which owns the consent and the erase a person asks for, tells this service to erase the memory of
 * the person's turns under a regulated pack.
 *
 * <p>{@code /internal/v1}: service-to-service, {@code SERVICE} authority only (ADR-035). A person
 * never calls this; they call the Studio service.
 */
@RestController
@RequestMapping("/internal/v1/protected-conversations")
class ProtectedConversationController {

  private final ProtectedConversationStore conversations;

  ProtectedConversationController(ProtectedConversationStore conversations) {
    this.conversations =
        Objects.requireNonNull(conversations, "ProtectedConversationStore required");
  }

  /**
   * Erases a person's conversations under a pack. Idempotent.
   *
   * @param request whose, and under which pack
   * @return counts of what was removed
   */
  @PostMapping("/erase")
  ErasureResponse erase(@RequestBody ErasureRequest request) {
    Erased erased = conversations.eraseOf(request.actorId(), request.packKey());
    return new ErasureResponse(erased.conversations(), erased.messages());
  }

  /**
   * @param actorId the person
   * @param packKey the regulated pack
   */
  record ErasureRequest(String actorId, String packKey) {}

  /**
   * @param conversations conversations removed
   * @param messages messages removed
   */
  record ErasureResponse(int conversations, int messages) {}
}
