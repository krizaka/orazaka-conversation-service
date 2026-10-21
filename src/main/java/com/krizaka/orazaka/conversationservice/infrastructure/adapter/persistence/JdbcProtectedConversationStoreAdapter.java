package com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The index of regulated conversations over this service's own table ({@code
 * orazaka_protected_conversation}), and the erasure that goes through it.
 *
 * <p>Every delete is one transaction over the messages, the chat session row and the index row, so
 * a crash cannot leave messages nobody can find again. The clock is the database's ({@code now()}).
 */
@Component
class JdbcProtectedConversationStoreAdapter implements ProtectedConversationStore {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transaction;

  JdbcProtectedConversationStoreAdapter(
      JdbcTemplate jdbcTemplate, TransactionTemplate transaction) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.transaction = Objects.requireNonNull(transaction, "TransactionTemplate cannot be null");
  }

  @Override
  public void record(String conversationId, String actorId, String installationId, String packKey) {
    jdbcTemplate.update(
        "INSERT INTO orazaka_protected_conversation"
            + " (conversation_id, actor_id, installation_id, pack_key) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (conversation_id) DO UPDATE SET last_seen_at = now()",
        conversationId,
        actorId,
        installationId,
        packKey);
  }

  @Override
  public Erased eraseOf(String actorId, String packKey) {
    return transaction.execute(status -> deleteOf(actorId, packKey));
  }

  private Erased deleteOf(String actorId, String packKey) {
    String mine =
        "SELECT conversation_id FROM orazaka_protected_conversation"
            + " WHERE actor_id = ? AND pack_key = ?";
    int messages =
        jdbcTemplate.update(
            "DELETE FROM orazaka_chat_messages WHERE conversation_id IN (" + mine + ")",
            actorId,
            packKey);
    jdbcTemplate.update(
        "DELETE FROM orazaka_chat_sessions WHERE user_id = ? AND id IN (" + mine + ")",
        actorId,
        actorId,
        packKey);
    int conversations =
        jdbcTemplate.update(
            "DELETE FROM orazaka_protected_conversation WHERE actor_id = ? AND pack_key = ?",
            actorId,
            packKey);
    return new Erased(conversations, messages);
  }

  @Override
  public Erased eraseOlderThan(int days) {
    return transaction.execute(status -> deleteOlderThan(days));
  }

  private Erased deleteOlderThan(int days) {
    String old =
        "SELECT conversation_id FROM orazaka_protected_conversation"
            + " WHERE last_seen_at < now() - make_interval(days => ?)";
    int messages =
        jdbcTemplate.update(
            "DELETE FROM orazaka_chat_messages WHERE conversation_id IN (" + old + ")", days);
    jdbcTemplate.update("DELETE FROM orazaka_chat_sessions WHERE id IN (" + old + ")", days);
    int conversations =
        jdbcTemplate.update(
            "DELETE FROM orazaka_protected_conversation"
                + " WHERE last_seen_at < now() - make_interval(days => ?)",
            days);
    return new Erased(conversations, messages);
  }
}
