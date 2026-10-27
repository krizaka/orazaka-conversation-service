-- orazaka-ai-engine#105: the HTTP door for products to run external checks. Applied once to an existing database
-- (orazaka_db), as its owner role. Idempotent. The audit gains the caller's tenant (a pending check is collected only
-- by the tenant that submitted it) and the provider's price; it accepts what the door can now write: PENDING, and
-- REFUSED / NOT_CONFIGURED.
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
