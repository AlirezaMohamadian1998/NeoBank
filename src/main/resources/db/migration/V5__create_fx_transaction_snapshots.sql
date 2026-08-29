CREATE TABLE fx_info
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    lock_id    VARCHAR(36) NOT NULL,

    CONSTRAINT pk_fx_info PRIMARY KEY (id),
    CONSTRAINT uk_fx_info_lock_id UNIQUE (lock_id),
    CONSTRAINT chk_fx_info_lock_id_format
        CHECK (REGEXP_LIKE(lock_id, '^[0-9a-f-]{36}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE fx_rates
(
    fx_info_id       BIGINT                                    NOT NULL,
    currency_context ENUM ('SOURCE', 'DESTINATION', 'REQUEST') NOT NULL,
    currency_code    ENUM ('USD', 'EUR', 'GBP', 'TRY')         NOT NULL,
    rate             DECIMAL(19, 8)                            NOT NULL,

    CONSTRAINT pk_fx_rates
        PRIMARY KEY (fx_info_id, currency_context),

    CONSTRAINT fk_fx_rates_fx_info
        FOREIGN KEY (fx_info_id) REFERENCES fx_info (id),

    CONSTRAINT chk_fx_rates_positive
        CHECK (rate > 0),

    CONSTRAINT chk_fx_rates_request_rate
        CHECK (currency_context <> 'REQUEST' OR rate = 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE bank_transactions
    ADD COLUMN fx_info_id BIGINT NULL,

    ADD CONSTRAINT uk_bank_transactions_fx_info
        UNIQUE (fx_info_id),

    ADD CONSTRAINT fk_bank_transactions_fx_info
        FOREIGN KEY (fx_info_id) REFERENCES fx_info (id);