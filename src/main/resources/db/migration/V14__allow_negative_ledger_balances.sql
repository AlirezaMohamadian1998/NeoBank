ALTER TABLE ledger_accounts
    ADD COLUMN negative_balance_allowed BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    DROP CHECK chk_ledger_accounts_balance_non_negative;

ALTER TABLE ledger_accounts
    ADD CONSTRAINT chk_ledger_accounts_balance_non_negative
        CHECK (balance >= 0 OR negative_balance_allowed);

ALTER TABLE ledger_entries
    DROP CHECK chk_ledger_entries_balance_after_non_negative;
