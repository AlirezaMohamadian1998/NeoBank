CREATE TABLE cards
(
    id                    BIGINT                                                     NOT NULL AUTO_INCREMENT,
    created_at            DATETIME(6)                                                NOT NULL,
    updated_at            DATETIME(6)                                                NOT NULL,
    card_reference        VARCHAR(32)                                                NOT NULL,
    last_four_digits      VARCHAR(4)                                                 NOT NULL,
    expiration_year_month VARCHAR(7)                                                 NOT NULL,
    status                ENUM ('INACTIVE', 'ACTIVE', 'FROZEN', 'CLOSED', 'BLOCKED') NOT NULL,

    CONSTRAINT pk_cards PRIMARY KEY (id),
    CONSTRAINT uk_card_reference UNIQUE (card_reference),
    CONSTRAINT chk_card_reference
        CHECK (REGEXP_LIKE(card_reference, '^[0-9a-f]{32}$', 'c')),
    CONSTRAINT chk_last_four_digits
        CHECK (REGEXP_LIKE(last_four_digits, '^[0-9]{4}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE debit_cards
(
    id         BIGINT NOT NULL,
    account_id BIGINT NOT NULL,

    CONSTRAINT pk_debit_cards PRIMARY KEY (id),
    CONSTRAINT fk_debit_cards_card FOREIGN KEY (id) REFERENCES cards (id),
    CONSTRAINT fk_debit_cards_account FOREIGN KEY (account_id) REFERENCES accounts (id),

    INDEX idx_debit_cards_account_id (account_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;