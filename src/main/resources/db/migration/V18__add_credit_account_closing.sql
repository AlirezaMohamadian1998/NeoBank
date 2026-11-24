ALTER TABLE credit_accounts
    ADD COLUMN payoff_account_id BIGINT NULL AFTER next_statement_date,
    ADD CONSTRAINT fk_credit_accounts_payoff_account
        FOREIGN KEY (payoff_account_id) REFERENCES accounts (id);

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
        'CREDIT_CARD_INTEREST',
        'CREDIT_CARD_PAYOUT'
        ) NOT NULL;
