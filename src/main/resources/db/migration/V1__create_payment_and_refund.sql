CREATE TABLE payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payment_status CHECK (status IN ('PAID', 'REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_payment_id UNIQUE (payment_id),
    CONSTRAINT uk_refund_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT ck_refund_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
