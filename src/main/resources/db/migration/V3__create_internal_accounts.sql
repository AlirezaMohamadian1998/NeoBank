CREATE TABLE internal_accounts
(
    id                       BIGINT                            NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6)                       NOT NULL,
    updated_at               DATETIME(6)                       NOT NULL,
    internal_account_purpose ENUM ('SETTLEMENT')               NOT NULL,
    currency_code            ENUM ('USD', 'EUR', 'GBP', 'TRY') NOT NULL,
    ledger_account_id        BIGINT                            NOT NULL,

    CONSTRAINT pk_internal_accounts PRIMARY KEY (id),

    CONSTRAINT fk_internal_accounts_ledger_accounts FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id),

    CONSTRAINT uk_internal_accounts_ledger_account_id UNIQUE (ledger_account_id),
    CONSTRAINT uk_internal_accounts_currency_purpose UNIQUE (currency_code, internal_account_purpose)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
