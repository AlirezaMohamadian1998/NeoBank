CREATE TABLE customers
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,

    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uk_customers_email UNIQUE (email),
    CONSTRAINT chk_customers_email_not_blank
        CHECK (CHAR_LENGTH(TRIM(email)) > 0),
    CONSTRAINT chk_customers_password_hash_not_blank
        CHECK (CHAR_LENGTH(TRIM(password_hash)) > 0),
    CONSTRAINT chk_customers_full_name_not_blank
        CHECK (CHAR_LENGTH(TRIM(full_name)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ledger_accounts
(
    id               BIGINT                            NOT NULL AUTO_INCREMENT,
    created_at       DATETIME(6)                       NOT NULL,
    updated_at       DATETIME(6)                       NOT NULL,
    version          BIGINT                            NOT NULL DEFAULT 0,
    ledger_reference VARCHAR(32)                       NOT NULL,
    type             ENUM ('ASSET', 'LIABILITY')       NOT NULL,
    currency         ENUM ('USD', 'EUR', 'GBP', 'TRY') NOT NULL,
    balance          DECIMAL(19, 2)                    NOT NULL DEFAULT 0.00,
    status           ENUM ('ACTIVE', 'CLOSED')         NOT NULL,

    CONSTRAINT pk_ledger_accounts PRIMARY KEY (id),
    CONSTRAINT uk_ledger_accounts_reference UNIQUE (ledger_reference),
    CONSTRAINT chk_ledger_accounts_reference_format
        CHECK (REGEXP_LIKE(ledger_reference, '^[0-9a-f]{32}$', 'c')),
    CONSTRAINT chk_ledger_accounts_balance_non_negative
        CHECK (balance >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE accounts
(
    id                BIGINT                      NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)                 NOT NULL,
    updated_at        DATETIME(6)                 NOT NULL,
    account_number    VARCHAR(14)                 NOT NULL,
    name              VARCHAR(80),
    account_type      ENUM ('CURRENT', 'SAVINGS') NOT NULL,
    customer_id       BIGINT                      NOT NULL,
    ledger_account_id BIGINT                      NOT NULL,

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uk_accounts_account_number UNIQUE (account_number),
    CONSTRAINT uk_accounts_ledger_account UNIQUE (ledger_account_id),
    CONSTRAINT chk_accounts_number_format
        CHECK (REGEXP_LIKE(account_number, '^[0-9]{14}$', 'c')),
    CONSTRAINT chk_accounts_name_not_blank
        CHECK (name IS NULL OR CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT fk_accounts_customer
        FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_accounts_ledger_account
        FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id),

    INDEX idx_accounts_customer_id (customer_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE bank_transactions
(
    id                 BIGINT                                    NOT NULL AUTO_INCREMENT,
    created_at         DATETIME(6)                               NOT NULL,
    updated_at         DATETIME(6)                               NOT NULL,
    requested_amount   DECIMAL(19, 2)                            NOT NULL,
    requested_currency ENUM ('USD', 'EUR', 'GBP', 'TRY')         NOT NULL,
    status             ENUM ('PENDING', 'COMPLETED', 'REVERSED') NOT NULL,
    transaction_type   ENUM (
        'DEPOSIT',
        'WITHDRAWAL',
        'TRANSFER',
        'LOAN_DISBURSEMENT',
        'LOAN_PAYMENT'
        )                                                        NOT NULL,
    reference          VARCHAR(32)                               NOT NULL,
    note               VARCHAR(255),

    CONSTRAINT pk_bank_transactions PRIMARY KEY (id),
    CONSTRAINT uk_bank_transactions_reference UNIQUE (reference),
    CONSTRAINT chk_bank_transactions_reference_format
        CHECK (REGEXP_LIKE(reference, '^[0-9a-f]{32}$', 'c')),
    CONSTRAINT chk_bank_transactions_requested_amount_positive
        CHECK (requested_amount > 0),
    CONSTRAINT chk_bank_transactions_note_not_blank
        CHECK (note IS NULL OR CHAR_LENGTH(TRIM(note)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ledger_entries
(
    id                BIGINT                            NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)                       NOT NULL,
    updated_at        DATETIME(6)                       NOT NULL,
    reference         VARCHAR(32)                       NOT NULL,
    amount            DECIMAL(19, 2)                    NOT NULL,
    balance_after     DECIMAL(19, 2)                    NOT NULL,
    direction         ENUM ('CREDIT', 'DEBIT')          NOT NULL,
    currency          ENUM ('USD', 'EUR', 'GBP', 'TRY') NOT NULL,
    ledger_account_id BIGINT                            NOT NULL,
    transaction_id    BIGINT                            NOT NULL,

    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT uk_ledger_entries_reference UNIQUE (reference),
    CONSTRAINT chk_ledger_entries_reference_format
        CHECK (REGEXP_LIKE(reference, '^[0-9a-f]{32}$', 'c')),
    CONSTRAINT chk_ledger_entries_amount_positive
        CHECK (amount > 0),
    CONSTRAINT chk_ledger_entries_balance_after_non_negative
        CHECK (balance_after >= 0),
    CONSTRAINT fk_ledger_entries_ledger_account
        FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id),
    CONSTRAINT fk_ledger_entries_transaction
        FOREIGN KEY (transaction_id) REFERENCES bank_transactions (id),

    INDEX idx_ledger_entries_ledger_account_id (ledger_account_id),
    INDEX idx_ledger_entries_transaction_id (transaction_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
