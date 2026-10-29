-- orazaka-ai-engine#169: a tool run is stored in the operator audit (who, feature key as target, tool as action, outcome),
-- and how long it took. Applied once to an existing database (orazaka_db), as its owner role. Idempotent.
ALTER TABLE operator_audit ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
