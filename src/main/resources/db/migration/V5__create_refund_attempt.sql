CREATE TABLE refund_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    operation VARCHAR(20) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    attempted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refund_attempt_refund FOREIGN KEY (refund_id) REFERENCES refund (id),
    CONSTRAINT ck_refund_attempt_operation_outcome CHECK (
        (operation = 'EXECUTE_REFUND' AND outcome IN ('SUCCEEDED', 'FAILED', 'TIMEOUT'))
        OR (operation = 'QUERY_PG' AND outcome IN ('SUCCEEDED', 'FAILED', 'NOT_FOUND', 'UNAVAILABLE'))
    )
);
