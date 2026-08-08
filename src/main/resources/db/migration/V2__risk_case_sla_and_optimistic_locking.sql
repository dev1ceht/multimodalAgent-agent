ALTER TABLE risk_cases
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN sla_due_at TIMESTAMP(6) NULL;

ALTER TABLE risk_case_referrals
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE risk_cases
SET sla_due_at = DATE_ADD(opened_at, INTERVAL 2 HOUR)
WHERE sla_due_at IS NULL;

CREATE INDEX idx_risk_case_sla ON risk_cases (status, sla_due_at);
