package com.krizaka.orazaka.conversationservice.domain.port;

/**
 * The conversations a regulated pack's turns were held in, and the right to erase them
 * (orazaka#301).
 *
 * <p>A voice or listening turn under an installation of a regulated pack is written to the
 * conversation memory like any turn - the next turn needs the previous one. That memory is keyed by
 * a conversation id the client chose and carries no owner, so without this index nothing could say
 * which messages are a person's wellbeing data, and neither the 30-day retention nor the erase the
 * statement promises could reach them. This port records <i>that</i> a conversation belongs to a
 * person's installation of a pack - never what was said.
 */
public interface ProtectedConversationStore {

  /**
   * Records that a conversation holds a turn of a regulated pack; idempotent, and refreshes the
   * conversation's last-seen time, which the retention window counts from.
   *
   * @param conversationId the conversation the turn is held in
   * @param actorId the person taking the turn
   * @param installationId the installation whose pack governs the turn
   * @param packKey the regulated pack
   */
  void record(String conversationId, String actorId, String installationId, String packKey);

  /**
   * Erases every conversation of one person under one pack: its messages, its chat session row and
   * the index row. Idempotent.
   *
   * @param actorId the person
   * @param packKey the pack
   * @return what was removed
   */
  Erased eraseOf(String actorId, String packKey);

  /**
   * Erases the conversations whose last turn is older than the retention window.
   *
   * @param days the window, in days
   * @return what was removed
   */
  Erased eraseOlderThan(int days);

  /**
   * What an erasure removed - counts only.
   *
   * @param conversations conversations removed
   * @param messages messages removed
   */
  record Erased(int conversations, int messages) {}
}
