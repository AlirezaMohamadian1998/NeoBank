ALTER TABLE cards
    ADD COLUMN per_transaction_limit DECIMAL(19, 2) NULL,
    ADD COLUMN daily_limit           DECIMAL(19, 2) NULL;

UPDATE cards c
    JOIN debit_cards dc ON dc.id = c.id
    JOIN accounts a ON a.id = dc.account_id
    JOIN ledger_accounts la ON la.id = a.ledger_account_id
SET c.per_transaction_limit = CASE la.currency
                                  WHEN 'USD' THEN 1000.00
                                  WHEN 'EUR' THEN 1000.00
                                  WHEN 'GBP' THEN 1000.00
                                  WHEN 'TRY' THEN 25000.00
        END,
    c.daily_limit           = CASE la.currency
                                  WHEN 'USD' THEN 2500.00
                                  WHEN 'EUR' THEN 2500.00
                                  WHEN 'GBP' THEN 2500.00
                                  WHEN 'TRY' THEN 50000.00
        END;

ALTER TABLE cards
    MODIFY COLUMN per_transaction_limit DECIMAL(19, 2) NOT NULL,
    MODIFY COLUMN daily_limit           DECIMAL(19, 2) NOT NULL,
    ADD CONSTRAINT chk_cards_limits_positive
        CHECK (per_transaction_limit > 0 AND daily_limit > 0),
    ADD CONSTRAINT chk_cards_per_transaction_within_daily
        CHECK (per_transaction_limit <= daily_limit);

CREATE TABLE card_daily_spend
(
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(6)    NOT NULL,
    updated_at   DATETIME(6)    NOT NULL,
    version      BIGINT         NOT NULL DEFAULT 0,
    card_id      BIGINT         NOT NULL,
    spend_date   DATE           NOT NULL,
    amount_spent DECIMAL(19, 2) NOT NULL,

    CONSTRAINT pk_card_daily_spend PRIMARY KEY (id),
    CONSTRAINT uk_card_daily_spend_card_date UNIQUE (card_id, spend_date),
    CONSTRAINT fk_card_daily_spend_card FOREIGN KEY (card_id) REFERENCES cards (id),
    CONSTRAINT chk_card_daily_spend_amount_non_negative
        CHECK (amount_spent >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
