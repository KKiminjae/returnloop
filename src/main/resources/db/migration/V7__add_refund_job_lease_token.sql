ALTER TABLE refund_job ADD COLUMN lease_token VARCHAR(36) NULL;

UPDATE refund_job SET lease_token = UUID() WHERE status = 'PROCESSING';

ALTER TABLE refund_job
    ADD CONSTRAINT ck_refund_job_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    );
