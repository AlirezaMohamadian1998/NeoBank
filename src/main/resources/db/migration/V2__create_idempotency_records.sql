CREATE TABLE idempotency_records
(
    id                  BIGINT                                                                          NOT NULL AUTO_INCREMENT,
    created_at          DATETIME(6)                                                                     NOT NULL,
    updated_at          DATETIME(6)                                                                     NOT NULL,
    customer_id         BIGINT                                                                          NOT NULL,
    idempotency_key     CHAR(32)                                                                        NOT NULL,
    request_hash        CHAR(64)                                                                        NOT NULL,
    bank_transaction_id BIGINT                                                                          NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),

    CONSTRAINT uk_idempotency_records_customer_key UNIQUE (customer_id, idempotency_key),
    CONSTRAINT uk_idempotency_records_bank_transaction UNIQUE (bank_transaction_id),

    CONSTRAINT fk_idempotency_records_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_idempotency_records_bank_transaction FOREIGN KEY (bank_transaction_id) REFERENCES bank_transactions (id),

    CONSTRAINT chk_idempotency_records_key_format
        CHECK (REGEXP_LIKE(idempotency_key, '^[a-f0-9]{32}$', 'c')),
    CONSTRAINT chk_idempotency_records_request_hash_format
        CHECK (REGEXP_LIKE(request_hash, '^[a-f0-9]{64}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;