ALTER TABLE bank_transactions
    MODIFY COLUMN transaction_type ENUM (
        'DEPOSIT',
        'WITHDRAWAL',
        'TRANSFER',
        'LOAN_DISBURSEMENT',
        'LOAN_PAYMENT',
        'DEBIT_CARD_PURCHASE',
        'DEBIT_CARD_REFUND',
        'CARD_PURCHASE',
        'CARD_REFUND'
        ) NOT NULL;

UPDATE bank_transactions
SET transaction_type = 'CARD_PURCHASE'
WHERE transaction_type = 'DEBIT_CARD_PURCHASE';

UPDATE bank_transactions
SET transaction_type = 'CARD_REFUND'
WHERE transaction_type = 'DEBIT_CARD_REFUND';

ALTER TABLE bank_transactions
    MODIFY COLUMN transaction_type ENUM (
        'DEPOSIT',
        'WITHDRAWAL',
        'TRANSFER',
        'LOAN_DISBURSEMENT',
        'LOAN_PAYMENT',
        'CARD_PURCHASE',
        'CARD_REFUND'
        ) NOT NULL;
