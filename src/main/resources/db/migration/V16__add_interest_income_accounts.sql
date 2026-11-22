ALTER TABLE ledger_accounts
    MODIFY COLUMN type ENUM ('ASSET', 'LIABILITY', 'INCOME') NOT NULL;

ALTER TABLE internal_accounts
    MODIFY COLUMN internal_account_purpose ENUM ('SETTLEMENT', 'INTEREST_INCOME') NOT NULL;

INSERT INTO ledger_accounts
(
    created_at,
    updated_at,
    version,
    ledger_reference,
    type,
    currency,
    balance,
    status
)
VALUES
    (
        UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6),
        0,
        '00000000000000000000000000000005',
        'INCOME',
        'TRY',
        0.00,
        'ACTIVE'
    ),
    (
        UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6),
        0,
        '00000000000000000000000000000006',
        'INCOME',
        'USD',
        0.00,
        'ACTIVE'
    ),
    (
        UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6),
        0,
        '00000000000000000000000000000007',
        'INCOME',
        'EUR',
        0.00,
        'ACTIVE'
    ),
    (
        UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6),
        0,
        '00000000000000000000000000000008',
        'INCOME',
        'GBP',
        0.00,
        'ACTIVE'
    );

INSERT INTO internal_accounts
(
    created_at,
    updated_at,
    internal_account_purpose,
    currency_code,
    ledger_account_id
)
SELECT
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6),
    'INTEREST_INCOME',
    ledger_account.currency,
    ledger_account.id
FROM ledger_accounts ledger_account
WHERE ledger_account.ledger_reference IN
      (
       '00000000000000000000000000000005',
       '00000000000000000000000000000006',
       '00000000000000000000000000000007',
       '00000000000000000000000000000008'
          );
