ALTER TABLE refund_attempt
    DROP CHECK ck_refund_attempt_operation_outcome,
    ADD CONSTRAINT ck_refund_attempt_operation_outcome CHECK (
        (operation = 'EXECUTE_REFUND' AND outcome IN ('SUCCEEDED', 'FAILED', 'TIMEOUT'))
        OR (operation = 'QUERY_PG' AND outcome IN ('SUCCEEDED', 'FAILED', 'NOT_FOUND', 'UNAVAILABLE'))
        OR (operation = 'RECONCILE' AND outcome = 'SUCCEEDED')
    );

CREATE TABLE refund_reconciliation_issue (
    refund_id BIGINT NOT NULL,
    mismatch_type VARCHAR(32) NOT NULL,
    internal_status VARCHAR(20) NOT NULL,
    internal_payment_status VARCHAR(20) NOT NULL,
    pg_status VARCHAR(20) NOT NULL,
    internal_payment_id BIGINT NOT NULL,
    pg_payment_id BIGINT NULL,
    internal_amount BIGINT NOT NULL,
    pg_amount BIGINT NULL,
    internal_idempotency_key VARCHAR(36) NOT NULL,
    pg_idempotency_key TEXT NULL,
    first_detected_at DATETIME(6) NOT NULL,
    last_detected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (refund_id, mismatch_type),
    CONSTRAINT fk_reconciliation_issue_refund FOREIGN KEY (refund_id) REFERENCES refund (id),
    CONSTRAINT ck_reconciliation_issue_detection_order CHECK (first_detected_at <= last_detected_at)
);
