-- ============================================================================
-- ORAZAKA — Local DB bootstrap · 20 — CONVERSATION CONTEXT
-- ----------------------------------------------------------------------------
-- Future owner: Conversation service (hosts business+core+interceptors — the
-- interactive sync path: chat sessions, token-by-token SSE, memory windows).
-- user_id is an OPAQUE ActorId — no FK into the identity context; cross-service
-- cleanup becomes event-driven after the split (no DB-level cascade).
-- ============================================================================

CREATE TABLE orazaka_chat_sessions (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chat_sessions_user ON orazaka_chat_sessions(user_id);

-- Durable conversation memory: chronological transcript per conversation, read by the
-- Memory interceptor to inject multi-turn context (short-term memory in Postgres; the
-- vector store handles long-term semantic RAG). Append-only log keyed by conversation_id
-- (no FK — a turn may be persisted for a conversation id whose session row is managed
-- independently; MemoryResolver.purge / explicit clear remove rows).
CREATE TABLE orazaka_chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chat_messages_conversation ON orazaka_chat_messages (conversation_id, id);
