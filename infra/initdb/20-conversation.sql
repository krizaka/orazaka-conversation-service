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

-- ADR-085: every operator write of the conversation service (model registry, app configuration): who/what/target/request/outcome (ids only).
CREATE TABLE IF NOT EXISTS operator_audit (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id VARCHAR(255),
    actor_label VARCHAR(255) NOT NULL,
    action VARCHAR(160) NOT NULL,
    target_id VARCHAR(255),
    request_id VARCHAR(64),
    outcome VARCHAR(12) NOT NULL DEFAULT 'ok',
    duration_ms BIGINT
);
CREATE INDEX IF NOT EXISTS idx_operator_audit_at ON operator_audit(at DESC);

-- orazaka-ai-engine#105: the checks' audit, with the caller's tenant (same block as infra/migrations/2026-10-27-product-checks-door.sql).
CREATE TABLE IF NOT EXISTS external_check_audit (
    id            BIGSERIAL    PRIMARY KEY,
    at            TIMESTAMPTZ  NOT NULL,
    actor_id      VARCHAR(255),
    run_id        UUID,
    provider      VARCHAR(60)  NOT NULL,
    purpose       VARCHAR(20)  NOT NULL CHECK (purpose IN ('COURT_RECORDS','CREDIT','IDENTITY')),
    key_source    VARCHAR(10)  CHECK (key_source IN ('USER','PLATFORM')),
    outcome       VARCHAR(20)  NOT NULL,
    refusal       VARCHAR(20),
    subject_ref   CHAR(64)     NOT NULL,
    duration_ms   BIGINT,
    provider_ref  VARCHAR(120)
);
ALTER TABLE external_check_audit ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
ALTER TABLE external_check_audit ADD COLUMN IF NOT EXISTS provider_cost_micros BIGINT;
ALTER TABLE external_check_audit DROP CONSTRAINT IF EXISTS external_check_audit_outcome_check;
ALTER TABLE external_check_audit ADD CONSTRAINT external_check_audit_outcome_check
    CHECK (outcome IN ('MATCH','NO_MATCH','PROVIDER_ERROR','TIMEOUT','PENDING','REFUSED'));
ALTER TABLE external_check_audit DROP CONSTRAINT IF EXISTS external_check_audit_refusal_check;
ALTER TABLE external_check_audit ADD CONSTRAINT external_check_audit_refusal_check
    CHECK (refusal IN ('NO_CONSENT','NO_KEY','BUDGET_EXHAUSTED','NOT_CONFIGURED'));
ALTER TABLE external_check_audit DROP CONSTRAINT IF EXISTS ck_external_check_audit_refusal;
ALTER TABLE external_check_audit ADD CONSTRAINT ck_external_check_audit_refusal
    CHECK ((outcome = 'REFUSED') = (refusal IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_external_check_audit_run ON external_check_audit (run_id);
CREATE INDEX IF NOT EXISTS idx_external_check_audit_actor_at ON external_check_audit (actor_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_external_check_audit_tenant_ref ON external_check_audit (tenant_id, provider_ref);

CREATE OR REPLACE FUNCTION external_check_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    RAISE EXCEPTION 'external_check_audit is append-only: a line is never rewritten';
END
$fn$;
DROP TRIGGER IF EXISTS trg_external_check_audit_append_only ON external_check_audit;
CREATE TRIGGER trg_external_check_audit_append_only BEFORE UPDATE ON external_check_audit
    FOR EACH ROW EXECUTE FUNCTION external_check_audit_append_only();
