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

-- The conversations a REGULATED pack's turns (voice, listening) are held in (orazaka#301). The memory above
-- is keyed by a conversation id the client chose and carries no owner, so this index says whose a protected
-- conversation is: it lets the person erase it ("Delete all my wellbeing data") and lets the retention
-- sweep age it out after the 30 days the consent statement states. It holds no content.
CREATE TABLE IF NOT EXISTS orazaka_protected_conversation (
    conversation_id VARCHAR(255) PRIMARY KEY,
    actor_id        VARCHAR(255) NOT NULL,
    installation_id VARCHAR(64)  NOT NULL,
    pack_key        VARCHAR(50)  NOT NULL,
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_protected_conversation_actor ON orazaka_protected_conversation (actor_id, pack_key);
CREATE INDEX IF NOT EXISTS idx_protected_conversation_seen ON orazaka_protected_conversation (last_seen_at);

-- ============================================================================
-- APP VERSION CONTRACT (krizaka-app-config) — the apps' minimum/latest version, maintenance, contract
-- range and flags: database parameters edited in the admin, with a reason and an audit trail.
-- Verbatim from krizaka-platform-kit/krizaka-app-config/src/main/resources/krizaka-app-config-schema.sql
-- (AppConfigSchemaTest keeps the two identical). Version 1 is seeded permissive.
-- ============================================================================
-- krizaka-app-config: the parameters of the app-version contract, one row per platform.
-- Version 1 is seeded here (permissive: no minimum, no maintenance, one contract); a product's own
-- migration publishes its real values through the admin API, never by editing the row by hand.
CREATE TABLE IF NOT EXISTS app_config (
    platform              VARCHAR(16)  PRIMARY KEY,
    min_supported_version VARCHAR(32)  NOT NULL DEFAULT '0.0.0',
    latest_version        VARCHAR(32)  NOT NULL DEFAULT '0.0.0',
    update_override       VARCHAR(8),
    store_url             TEXT,
    maintenance_on        BOOLEAN      NOT NULL DEFAULT FALSE,
    maintenance_message   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    message               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    contract_min          INTEGER      NOT NULL DEFAULT 1,
    contract_max          INTEGER      NOT NULL DEFAULT 1,
    contract_sunset       DATE,
    flags                 JSONB        NOT NULL DEFAULT '{}'::jsonb,
    revision              INTEGER      NOT NULL DEFAULT 1,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            VARCHAR(255) NOT NULL DEFAULT 'seed',
    CONSTRAINT app_config_platform_known CHECK (platform IN ('ios', 'android', 'macos', 'windows', 'linux', 'web')),
    CONSTRAINT app_config_contract_range CHECK (contract_min >= 1 AND contract_max >= contract_min)
);

-- Every published revision, append-only: the audit trail and the source of a rollback.
CREATE TABLE IF NOT EXISTS app_config_history (
    id                    BIGSERIAL    PRIMARY KEY,
    platform              VARCHAR(16)  NOT NULL,
    revision              INTEGER      NOT NULL,
    min_supported_version VARCHAR(32)  NOT NULL,
    latest_version        VARCHAR(32)  NOT NULL,
    update_override       VARCHAR(8),
    store_url             TEXT,
    maintenance_on        BOOLEAN      NOT NULL,
    maintenance_message   JSONB        NOT NULL,
    message               JSONB        NOT NULL,
    contract_min          INTEGER      NOT NULL,
    contract_max          INTEGER      NOT NULL,
    contract_sunset       DATE,
    flags                 JSONB        NOT NULL,
    reason                TEXT         NOT NULL,
    changed_by            VARCHAR(255) NOT NULL,
    changed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT app_config_history_revision UNIQUE (platform, revision)
);

INSERT INTO app_config (platform)
VALUES ('ios'), ('android'), ('macos'), ('windows'), ('linux'), ('web')
ON CONFLICT (platform) DO NOTHING;

INSERT INTO app_config_history (platform, revision, min_supported_version, latest_version, update_override,
                                store_url, maintenance_on, maintenance_message, message, contract_min,
                                contract_max, contract_sunset, flags, reason, changed_by, changed_at)
SELECT platform, revision, min_supported_version, latest_version, update_override, store_url, maintenance_on,
       maintenance_message, message, contract_min, contract_max, contract_sunset, flags, 'seed', updated_by,
       updated_at
FROM app_config
ON CONFLICT (platform, revision) DO NOTHING;
