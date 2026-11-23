ALTER TABLE credit_accounts
    ADD COLUMN annual_interest_rate DECIMAL(6, 4) NULL AFTER credit_limit,
    ADD COLUMN next_statement_date  DATE          NULL AFTER annual_interest_rate;

UPDATE credit_accounts credit_account
    JOIN ledger_accounts ledger_account ON ledger_account.id = credit_account.ledger_account_id
SET credit_account.annual_interest_rate = CASE ledger_account.currency
                                              WHEN 'TRY' THEN 0.5100
                                              ELSE 0.2499
    END,
    credit_account.next_statement_date  = DATE_ADD(
            DATE_ADD(
                    DATE_SUB(DATE(credit_account.created_at), INTERVAL DAY(credit_account.created_at) - 1 DAY),
                    INTERVAL LEAST(DAY(credit_account.created_at), 28) - 1 DAY
            ),
            INTERVAL 1 MONTH
                                          );

ALTER TABLE credit_accounts
    MODIFY COLUMN annual_interest_rate DECIMAL(6, 4) NOT NULL,
    MODIFY COLUMN next_statement_date DATE NOT NULL,
    ADD CONSTRAINT chk_credit_accounts_annual_interest_rate_range
        CHECK (annual_interest_rate >= 0 AND annual_interest_rate < 1),
    ADD INDEX idx_credit_accounts_next_statement_date (next_statement_date);

CREATE TABLE credit_statements
(
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)    NOT NULL,
    updated_at              DATETIME(6)    NOT NULL,
    statement_reference     VARCHAR(32)    NOT NULL,
    credit_account_id       BIGINT         NOT NULL,
    period_start            DATE           NOT NULL,
    statement_date          DATE           NOT NULL,
    due_date                DATE           NOT NULL,
    opening_balance         DECIMAL(19, 2) NOT NULL,
    closing_balance         DECIMAL(19, 2) NOT NULL,
    average_daily_balance   DECIMAL(19, 2) NOT NULL,
    interest_charged        DECIMAL(19, 2) NOT NULL,
    minimum_payment         DECIMAL(19, 2) NOT NULL,
    interest_transaction_id BIGINT         NULL,

    CONSTRAINT pk_credit_statements PRIMARY KEY (id),
    CONSTRAINT uk_credit_statements_reference UNIQUE (statement_reference),
    CONSTRAINT uk_credit_statements_account_statement_date UNIQUE (credit_account_id, statement_date),
    CONSTRAINT uk_credit_statements_interest_transaction UNIQUE (interest_transaction_id),
    CONSTRAINT fk_credit_statements_credit_account
        FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
    CONSTRAINT fk_credit_statements_interest_transaction
        FOREIGN KEY (interest_transaction_id) REFERENCES bank_transactions (id),
    CONSTRAINT chk_credit_statements_reference_format
        CHECK (REGEXP_LIKE(statement_reference, '^[0-9a-f]{32}$', 'c')),
    CONSTRAINT chk_credit_statements_period
        CHECK (period_start < statement_date),
    CONSTRAINT chk_credit_statements_due_date
        CHECK (due_date > statement_date),
    CONSTRAINT chk_credit_statements_average_daily_balance_non_negative
        CHECK (average_daily_balance >= 0),
    CONSTRAINT chk_credit_statements_interest_charged_non_negative
        CHECK (interest_charged >= 0),
    CONSTRAINT chk_credit_statements_minimum_payment_non_negative
        CHECK (minimum_payment >= 0),
    CONSTRAINT chk_credit_statements_interest_transaction_matches_interest
        CHECK ((interest_charged = 0) = (interest_transaction_id IS NULL))
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
        'CREDIT_CARD_REPAYMENT',
        'CREDIT_CARD_INTEREST'
        ) NOT NULL;
