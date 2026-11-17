ALTER TABLE card_purchase_details
    ADD COLUMN card_last_four_digits VARCHAR(4) NULL AFTER card_reference;

UPDATE card_purchase_details cpd
    JOIN cards c ON c.card_reference = cpd.card_reference
SET cpd.card_last_four_digits = c.last_four_digits;

ALTER TABLE card_purchase_details
    MODIFY COLUMN card_last_four_digits VARCHAR(4) NOT NULL,
    ADD CONSTRAINT chk_card_purchase_details_card_last_four_digits_format
        CHECK (REGEXP_LIKE(card_last_four_digits, '^[0-9]{4}$', 'c')),
    ADD CONSTRAINT fk_card_purchase_details_card
        FOREIGN KEY (card_reference) REFERENCES cards (card_reference);
