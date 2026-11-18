ALTER TABLE bank_transactions
    MODIFY COLUMN status ENUM (
        'PENDING',
        'COMPLETED',
        'REVERSED',
        'REFUNDED'
        ) NOT NULL,
    MODIFY COLUMN transaction_type ENUM (
        'DEPOSIT',
        'WITHDRAWAL',
        'TRANSFER',
        'LOAN_DISBURSEMENT',
        'LOAN_PAYMENT',
        'DEBIT_CARD_PURCHASE',
        'DEBIT_CARD_REFUND'
        ) NOT NULL;

CREATE TABLE card_refund_details
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    bank_transaction_id      BIGINT      NOT NULL,
    card_purchase_details_id BIGINT      NOT NULL,

    CONSTRAINT pk_card_refund_details PRIMARY KEY (id),
    CONSTRAINT uk_card_refund_details_bank_transaction UNIQUE (bank_transaction_id),
    CONSTRAINT uk_card_refund_details_card_purchase_details UNIQUE (card_purchase_details_id),
    CONSTRAINT fk_card_refund_details_bank_transaction
        FOREIGN KEY (bank_transaction_id) REFERENCES bank_transactions (id),
    CONSTRAINT fk_card_refund_details_card_purchase_details
        FOREIGN KEY (card_purchase_details_id) REFERENCES card_purchase_details (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
