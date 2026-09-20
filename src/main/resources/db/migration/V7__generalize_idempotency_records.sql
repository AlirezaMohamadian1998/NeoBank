ALTER TABLE idempotency_records
    ADD COLUMN result_reference VARCHAR(32) NULL AFTER request_hash;

UPDATE idempotency_records ir
    JOIN bank_transactions bt ON bt.id = ir.bank_transaction_id
SET ir.result_reference = bt.reference;

ALTER TABLE idempotency_records
    MODIFY COLUMN result_reference VARCHAR(32) NOT NULL,
    ADD CONSTRAINT chk_idempotency_records_result_reference_format
        CHECK (REGEXP_LIKE(result_reference, '^[0-9a-f]{32}$', 'c'));

ALTER TABLE idempotency_records
    DROP FOREIGN KEY fk_idempotency_records_bank_transaction,
    DROP INDEX uk_idempotency_records_bank_transaction,
    DROP COLUMN bank_transaction_id;
