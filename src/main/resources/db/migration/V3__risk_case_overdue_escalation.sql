ALTER TABLE risk_cases
    ADD COLUMN overdue_escalated_at TIMESTAMP(6) NULL;

ALTER TABLE delivery_tasks
    ADD COLUMN risk_case_id BIGINT NULL;

CREATE INDEX idx_delivery_task_risk_case ON delivery_tasks (risk_case_id);

ALTER TABLE delivery_tasks
    ADD CONSTRAINT fk_delivery_task_risk_case
        FOREIGN KEY (risk_case_id) REFERENCES risk_cases (id);
