ALTER TABLE bank_transactions
    MODIFY COLUMN transaction_type ENUM (
        'DEPOSIT',
        'WITHDRAWAL',
        'TRANSFER',
        'LOAN_DISBURSEMENT',
        'LOAN_PAYMENT',
        'DEBIT_CARD_PURCHASE'
        ) NOT NULL;

CREATE TABLE card_purchase_details
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    bank_transaction_id BIGINT       NOT NULL,
    merchant_name       VARCHAR(128) NOT NULL,
    card_reference      VARCHAR(32)  NOT NULL,

    CONSTRAINT pk_card_purchase_details PRIMARY KEY (id),
    CONSTRAINT uk_card_purchase_details_bank_transaction UNIQUE (bank_transaction_id),
    CONSTRAINT fk_card_purchase_details_bank_transaction
        FOREIGN KEY (bank_transaction_id) REFERENCES bank_transactions (id),
    CONSTRAINT chk_card_purchase_details_merchant_name_not_blank
        CHECK (CHAR_LENGTH(TRIM(merchant_name)) > 0),
    CONSTRAINT chk_card_purchase_details_card_reference_format
        CHECK (REGEXP_LIKE(card_reference, '^[0-9a-f]{32}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
