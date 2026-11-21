CREATE TABLE credit_accounts
(
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    account_number    VARCHAR(14)    NOT NULL,
    customer_id       BIGINT         NOT NULL,
    ledger_account_id BIGINT         NOT NULL,
    credit_limit      DECIMAL(19, 2) NOT NULL,

    CONSTRAINT pk_credit_accounts PRIMARY KEY (id),
    CONSTRAINT uk_credit_accounts_account_number UNIQUE (account_number),
    CONSTRAINT uk_credit_accounts_ledger_account UNIQUE (ledger_account_id),
    CONSTRAINT chk_credit_accounts_number_format
        CHECK (REGEXP_LIKE(account_number, '^[0-9]{14}$', 'c')),
    CONSTRAINT chk_credit_accounts_credit_limit_positive
        CHECK (credit_limit > 0),
    CONSTRAINT fk_credit_accounts_customer
        FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_credit_accounts_ledger_account
        FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id),

    INDEX idx_credit_accounts_customer_id (customer_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE credit_cards
(
    id                BIGINT NOT NULL,
    credit_account_id BIGINT NOT NULL,

    CONSTRAINT pk_credit_cards PRIMARY KEY (id),
    CONSTRAINT fk_credit_cards_card FOREIGN KEY (id) REFERENCES cards (id),
    CONSTRAINT fk_credit_cards_credit_account FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),

    INDEX idx_credit_cards_credit_account_id (credit_account_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE bank_transactions
    MODIFY COLUMN transaction_type ENUM (
        'DEPOSIT',
        'WITHDRAWAL',
        'TRANSFER',
        'LOAN_DISBURSEMENT',
        'LOAN_PAYMENT',
        'CARD_PURCHASE',
        'CARD_REFUND',
        'CREDIT_CARD_REPAYMENT'
        ) NOT NULL;
