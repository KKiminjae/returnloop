ALTER TABLE refund_attempt
    DROP CHECK ck_refund_attempt_operation_outcome,
    ADD CONSTRAINT ck_refund_attempt_operation_outcome CHECK (
        (operation = 'EXECUTE_REFUND' AND outcome IN ('SUCCEEDED', 'FAILED', 'TIMEOUT'))
        OR (operation = 'QUERY_PG' AND outcome IN ('SUCCEEDED', 'FAILED', 'NOT_FOUND', 'UNAVAILABLE'))
        OR (operation = 'RECONCILE' AND outcome IN ('SUCCEEDED', 'FAILED'))
    );
