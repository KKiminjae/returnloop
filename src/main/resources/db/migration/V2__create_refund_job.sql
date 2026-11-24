CREATE TABLE refund_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_job_refund_id UNIQUE (refund_id),
    CONSTRAINT fk_refund_job_refund FOREIGN KEY (refund_id) REFERENCES refund (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
