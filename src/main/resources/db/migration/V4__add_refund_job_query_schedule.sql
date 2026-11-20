ALTER TABLE refund_job
    DROP CHECK ck_refund_job_status,
    ADD CONSTRAINT ck_refund_job_status CHECK (status IN ('PENDING', 'COMPLETED', 'NEEDS_REVIEW')),
    ADD COLUMN operation VARCHAR(20) NOT NULL DEFAULT 'EXECUTE_REFUND',
    ADD COLUMN query_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_run_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_refund_job_operation CHECK (operation IN ('EXECUTE_REFUND', 'QUERY_PG')),
    ADD CONSTRAINT ck_refund_job_query_count CHECK (query_count BETWEEN 0 AND 3);
