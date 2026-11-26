ALTER TABLE refund_job
    DROP CHECK ck_refund_job_status,
    ADD COLUMN lease_expires_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_refund_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'NEEDS_REVIEW')),
    ADD CONSTRAINT ck_refund_job_lease CHECK (
        (status = 'PROCESSING' AND lease_expires_at IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_expires_at IS NULL)
    );
