# NeoBank

NeoBank is a work-in-progress banking backend built as a personal portfolio project with Java and Spring Boot. The project focuses on reliable money movement, ledger integrity, transaction safety, and clean domain boundaries rather than production banking integrations.

## Current features

- Customer registration and JWT-based authentication
- Current and savings accounts in TRY, USD, EUR, and GBP
- Deposits, withdrawals, and account-to-account transfers, including cross-currency operations
- Short-lived, customer-scoped FX rate locks for cross-currency money movement
- Debit and credit card issuance, retrieval, and lifecycle management (activate, freeze, block, close)
- Card purchases at internal merchants and external merchants through settlement accounts, through one endpoint for both card types
- Credit cards backed by a credit account with a credit limit, repayments from current accounts (cross-currency with an FX rate lock), and credit balances after refunds
- Monthly credit card statements with a minimum payment and interest on the average daily balance, with a grace period, closed by a daily scheduled job
- Full refunds of card purchases by the merchant account's owner, at current FX rates for cross-currency purchases
- Per-transaction and daily card spending limits, with bank defaults and maximums per currency that customers can adjust
- Paginated and filterable transaction history for current accounts, credit cards, and single cards, plus the lines of each credit card statement
- Double-entry ledger postings with internal settlement accounts
- Idempotent money-movement and card requests
- Optimistic concurrency control with automatic retries and transactional rollback guarantees, plus a pessimistic lock so a purchase can be refunded only once
- Database versioning with Flyway
- Open Exchange Rates integration behind a provider abstraction, with cached and normalized rates

## Architecture

NeoBank is a Spring Boot monolith organized by feature.

- Operation-specific services coordinate deposits, withdrawals, transfers, card purchases, card refunds, and credit card repayments.
- `LedgerPostingService` is the central boundary for balance changes and ledger-entry creation.
- A `BankTransaction` groups its ledger entries and can complete only when its postings are balanced.
- Internal settlement accounts provide the bank side of deposits, withdrawals, and external card purchases.
- Idempotency records store a hash of a canonical request and a reference to the original result, so retries replay instead of repeating.
- Ledger accounts use `@Version`, and conflicting writes are retried with Spring's `@Retryable`.
- Cross-currency transactions store an FX snapshot of the rates used.
- A refund is a new transaction with its own ledger entries; the original purchase keeps its entries and only moves from `COMPLETED` to `REFUNDED`.
- Debit and credit cards share a `Card` base class. Purchases and refunds work on any card through its linked ledger account: a debit card's funding account, or a credit card's credit account.
- A credit account owns an `ASSET` ledger account: the bank is owed what the customer spends. That ledger account is flagged to allow a negative balance, which is how a credit balance is stored; every other ledger account still rejects one.
- Card spending limits live on the card, while daily usage is a separate running total per card and day. That total uses `@Version` and the same retries as ledger accounts, so concurrent purchases can't exceed the daily limit.
- FX provider responses are validated, cached with Caffeine, and normalized to the requested base currency.
- A daily scheduled job closes credit card statements. Each credit account is closed in its own transaction under a pessimistic row lock, and a unique constraint on the account and statement date backs it up, so several application instances can run the job at once without creating a statement twice.
- Interest is a `CREDIT_CARD_INTEREST` transaction that debits the credit account and credits an internal interest income account, a new `INCOME` ledger type.
- Audit timestamps come from the application's `Clock` bean, so tests can move time.
- Every transaction list is a page of ledger entries mapped to the same row types, so a purchase, refund, or repayment looks the same whichever list it appears in. Card details for purchase and refund rows are loaded with one query per page for each.

## Technology stack

- Java 21 and Spring Boot 4
- Spring MVC, Spring Security, OAuth2 Resource Server, and JWT
- Spring Data JPA and Hibernate
- MySQL and Flyway
- Spring `RestClient` and Open Exchange Rates
- Caffeine cache
- Maven and Docker Compose
- JUnit 5, AssertJ, Mockito, MockMvc, and Testcontainers

## Running locally

Requirements:

- Java 21
- Docker
- An [Open Exchange Rates](https://openexchangerates.org/) app ID

Set the required environment variables:

```text
JWT_SECRET=<Base64-encoded secret of at least 32 bytes>
OPEN_EXCHANGE_RATES_APP_ID=<your app id>
```

Start MySQL:

```bash
docker compose up -d
```

Run the application with the local profile:

```bash
bash mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows, use `mvnw.cmd` instead of `bash mvnw`.

## Main API areas

| Area | Endpoint examples |
|---|---|
| Customers | `POST /api/customers`, `GET /api/customers/me` |
| Authentication | `POST /api/auth/login` |
| Accounts | `POST /api/accounts`, `GET /api/accounts`, `GET /api/accounts/{accountNumber}` |
| Deposits | `POST /api/accounts/{accountNumber}/deposits` |
| Withdrawals | `POST /api/accounts/{accountNumber}/withdrawals` |
| Transfers | `POST /api/accounts/{accountNumber}/transfers` |
| Transaction history | `GET /api/transactions`, `GET /api/credit-cards/{cardReference}/transactions`, `GET /api/cards/{cardReference}/transactions` |
| FX rate locks | `POST /api/fx/rate-locks?base=USD` |
| Debit cards | `POST /api/debit-cards/{accountNumber}`, `GET /api/debit-cards`, `GET /api/debit-cards/{cardReference}` |
| Credit cards | `POST /api/credit-cards`, `GET /api/credit-cards`, `GET /api/credit-cards/{cardReference}` |
| Card lifecycle | `PATCH /api/debit-cards/{cardReference}/status/{status}`, `PATCH /api/credit-cards/{cardReference}/status/{status}` |
| Card spending limits | `PUT /api/debit-cards/{cardReference}/spending-limits`, `PUT /api/credit-cards/{cardReference}/spending-limits` |
| Card purchases | `POST /api/cards/{cardReference}/payments` |
| Card refunds | `POST /api/accounts/{merchantAccountNumber}/card-refunds/{purchaseReference}` |
| Credit card repayments | `POST /api/credit-cards/{cardReference}/repayments` |
| Credit card statements | `GET /api/credit-cards/{cardReference}/statements`, `GET /api/credit-cards/{cardReference}/statements/{statementReference}`, `GET /api/credit-cards/{cardReference}/statements/{statementReference}/lines` |

Protected endpoints require a bearer token. Money-movement, card issuance, card purchase, card refund, and credit card repayment requests require an `Idempotency-Key` header. Cross-currency deposits, withdrawals, transfers, and credit card repayments can reference an FX rate lock, which expires after five minutes.

Every row in a transaction list includes the `transactionReference` of the movement it belongs to. Merchants use it as the `purchaseReference` when refunding, and a cardholder's refund row carries the `purchaseReference` of the purchase it reverses.

### Accounts

- An account is `CURRENT` or `SAVINGS`, with an optional name.
- Its currency is chosen when it is opened and never changes.
- Account numbers are random 14-digit numbers, unique across current, savings, and credit accounts.

### Deposits and withdrawals

- The request names the amount and the currency of the cash (`requestedCurrency`).
- The bank side is the internal settlement account in that currency. The customer's account is credited or debited in its own currency.
- When the two currencies differ, the request must include an FX rate lock whose base is the requested currency. A lock sent for a same-currency operation is rejected.
- A withdrawal larger than the balance is rejected with `409 Conflict`.

### Transfers

- A transfer can go to any NeoBank account by its account number, including other customers' accounts, but not to the source account itself.
- The amount is given in the request's `currency`, which can differ from both accounts. The source is debited and the destination credited, each in its own currency.
- If any of the three currencies differ, an FX rate lock with the request's currency as its base is required.
- A transfer larger than the source balance is rejected with `409 Conflict`.

### FX rate locks

- `POST /api/fx/rate-locks?base=USD` returns the current rates from the base currency to every supported currency, with a lock ID.
- A lock belongs to the customer who created it and can be used for several operations until it expires after five minutes.
- An unknown or expired lock is rejected with `409 Conflict`.

### Card lifecycle

New cards start as `INACTIVE`. `PATCH /api/debit-cards/{cardReference}/status/{status}` (or `/api/credit-cards/...`) moves them between statuses:

| Target status | Allowed from |
|---|---|
| `ACTIVE` | `INACTIVE` (activate) or `FROZEN` (unfreeze) |
| `FROZEN` | `ACTIVE` |
| `BLOCKED` | any status except `BLOCKED` and `CLOSED` |
| `CLOSED` | any status except `CLOSED` |

- Expired cards can only be closed.
- A card can't go back to `INACTIVE`.
- Other changes are rejected with `409 Conflict`.

### Card purchases

- `POST /api/cards/{cardReference}/payments` charges debit and credit cards alike. The response's `linkedAccountNumber` is the account that was charged.
- Only active, unexpired cards can make purchases. Other cards are rejected with `409 Conflict`.
- With a `merchantAccountNumber`, the money goes to that NeoBank account. Without one, the merchant is outside NeoBank and the money goes to the settlement account in the purchase currency.
- A card can't pay into its own linked account.
- Cross-currency purchases use the current rates at the time of the purchase, without a rate lock. The rates used are stored with the transaction.
- Purchases are checked against the card's spending limits, and credit card purchases also against the available credit.

### Credit cards

- `POST /api/credit-cards` with a `currency` issues a credit card together with a new credit account in that currency. The credit limit is the bank default for the currency, configured under `credit-account.default-credit-limits`. Customers can't change it.
- The credit account holds the debt, so it carries on when a card is frozen, blocked, or closed. Closing a card doesn't require a zero balance.
- Card responses include `creditLimit`, `balance` (what the customer owes), `availableCredit` (limit minus balance), `annualInterestRate`, and `nextStatementDate`.
- A purchase that would take the balance over the credit limit is rejected with `409 Conflict`. The check uses the amount actually debited, after any FX conversion.
- A refund lowers the balance. A refund after the purchase was already repaid leaves a negative balance, a credit balance: the bank owes the customer, and the available credit is higher than the limit until it is spent.
- `POST /api/credit-cards/{cardReference}/repayments` moves money from one of the customer's current accounts to the credit account. The `amount` is in the card's currency. It works whatever the card's status.
- If the current account is in another currency, the request must include an FX rate lock whose base is the card's currency, and the account is debited the converted amount. A lock sent for a same-currency repayment is rejected.
- A repayment larger than the outstanding balance is rejected with `409 Conflict`, as is one larger than the current account's balance.
- Repayments appear as `CREDIT_CARD_REPAYMENT` in both the current account's history and the credit card's transactions.

### Credit card statements

- **Cycle.** Each credit account gets a statement once a month, on the day of the month it was opened, capped at the 28th. An account opened on January 31 gets statements on the 28th. A cycle runs from the previous statement date (or the opening date) up to, but not including, the next statement date.
- **Job.** A job runs every day at 00:15 UTC and closes every account whose statement date has come. If the job missed days, it catches up and closes each missed cycle in order. The schedule can be changed with `credit-statements.cron`, and `scheduling.enabled: false` turns scheduling off. Days are UTC.
- **Due date.** 25 days after the statement date (`credit-account.payment-due-days`). It matters twice:
  - Paying the statement's closing balance in full by the due date keeps the grace period.
  - The minimum payment is due by then. Missing it has no consequence yet beyond the interest.
- **Paid in full** means that repayments and refunds between the statement date and the due date (inclusive) add up to at least the closing balance. A statement with a closing balance of 0 or less is always paid in full. New purchases made in that time don't have to be paid.
- **Grace period.** It works like a switch:
  - It starts ON. While it's ON, a cycle has no interest.
  - It turns OFF when a statement isn't paid in full by its due date. That cycle and the following ones are charged interest.
  - It turns back ON after a statement is paid in full by its due date, but only from the next cycle. The cycle in which the customer paid still has interest, up to the day of the payment.
  - When a cycle closes, it has no interest only if both the previous statement and the one before it were paid in full.
- **Interest** = the sum of the daily balances × annual rate / 365, rounded to 2 decimals. The daily balance is the balance at the end of each day. A day with a credit balance counts as 0. The rate is the bank default per currency (`credit-account.default-annual-interest-rates`). Interest is added to the balance, so unpaid interest is charged interest in later cycles.
- **Interest can take the balance over the credit limit.**
- **Minimum payment** = 3% of the closing balance (`minimum-payment-percent`), at least the currency's floor (`minimum-payment-floors`, for example 25 USD), and at most the closing balance. It's 0 when the closing balance is 0 or less.
- A statement shows the period, statement date, due date, opening and closing balance, average daily balance, interest charged, minimum payment, and currency. `GET /api/credit-cards/{cardReference}/statements` lists them newest first, with pagination.
- **Lines.** `GET /api/credit-cards/{cardReference}/statements/{statementReference}/lines` lists the movements of the cycle, oldest first, with pagination: purchases, refunds, repayments, and the statement's own interest charge as the last line. The opening balance plus the lines always equals the closing balance.
- A statement's interest is posted just after its statement date (when the job runs), so by date it falls in the next cycle. The lines put it on the statement that charged it, and leave the previous statement's interest out.

#### Worked example

A USD card with a 24.99% annual rate, opened on January 15. Statements come on the 15th and are due 25 days later.

| Date | What happens | Grace period |
|---|---|---|
| Jan 20 | Buys 600 | ON |
| **Feb 15** | **Statement 1**: closing 600, minimum 25, due Mar 12. First cycle, no interest. | ON |
| Mar 12 | Due date of statement 1. Nothing paid. | turns OFF |
| **Mar 15** | **Statement 2**: interest 11.50 (600 for 28 days), closing 611.50, minimum 25, due Apr 9. | OFF |
| Mar 30 | Pays 611.50, statement 2 in full, before its due date. | stays OFF until the next cycle |
| **Apr 15** | **Statement 3**: residual interest 6.28 (611.50 for the 15 days until the payment, 0 after), closing 6.28, minimum 6.28, due May 10. | turns ON |
| Apr 20 | Pays 6.28, statement 3 in full. | ON |
| Apr 25 | Buys 100 | ON |
| **May 15** | **Statement 4**: no interest, closing 100, due Jun 9. The Apr 25 purchase is interest-free if paid by Jun 9. | ON |

If the customer hadn't paid the 6.28 by May 10, statement 4 would have charged interest on the whole cycle again, including the new 100 purchase.

Each case in one table:

| Cycle closing | Before previous paid in full? | Previous paid in full? | Interest |
|---|---|---|---|
| Statement 1 (Feb 15) | none | none | no |
| Statement 2 (Mar 15) | none | no (statement 1 missed) | yes |
| Statement 3 (Apr 15) | no (statement 1) | yes (statement 2) | yes, residual |
| Statement 4 (May 15) | yes (statement 2) | yes (statement 3) | no |

#### One closing, step by step

How statement 3 is closed on April 15:

1. The job finds the account, because its next statement date (Apr 15) has come, and locks the account row.
2. **Period:** Mar 15, the date of statement 2, up to Apr 15. That's 31 days.
3. **Opening balance:** 611.50, the closing balance of statement 2.
4. **Entries in the period:** the Mar 30 repayment of 611.50. The 11.50 interest of statement 2 was also posted on Mar 15, but interest entries are skipped, because the 611.50 already includes it.
5. **Daily balances:**

   | Days | Balance | Days × balance |
   |---|---|---|
   | Mar 15 – Mar 29 (15 days) | 611.50 | 9,172.50 |
   | Mar 30 – Apr 14 (16 days) | 0.00 | 0.00 |
   | **Sum** | | **9,172.50** |

   Average daily balance = 9,172.50 / 31 = 295.89.
6. **Grace checks:**
   - Statement 1 (before the previous one): repaid between Feb 15 and Mar 12 = 0, less than 600. Not paid in full, so the grace period was OFF when this cycle started.
   - Statement 2 (previous): repaid between Mar 15 and Apr 9 = 611.50. Paid in full, so the grace period comes back next cycle.
   - One of them wasn't paid in full, so interest is charged.
7. **Interest:** 9,172.50 × 0.2499 / 365 = 6.28, posted as a `CREDIT_CARD_INTEREST` transaction.
8. **Closing balance:** 0.00 + 6.28 = 6.28.
9. **Minimum payment:** 3% of 6.28 is 0.19, raised to the 25 floor, then capped at the closing balance: 6.28.
10. **Due date:** Apr 15 + 25 days = May 10. **Next statement date:** May 15.

#### Statement lines

The lines of statements 2 and 3 from the example:

| Statement | Opening | Lines | Closing |
|---|---|---|---|
| Statement 2 (Feb 15 – Mar 15) | 600.00 | `CREDIT_CARD_INTEREST` 11.50 DEBIT (posted Mar 15) | 611.50 |
| Statement 3 (Mar 15 – Apr 15) | 611.50 | `CREDIT_CARD_REPAYMENT` 611.50 CREDIT (Mar 30), `CREDIT_CARD_INTEREST` 6.28 DEBIT (posted Apr 15) | 6.28 |

On the credit account, DEBIT raises what the customer owes and CREDIT lowers it. The 11.50 posted on Mar 15 falls inside statement 3's period by date, but it belongs to statement 2, so it is listed only there.

### Transaction history

| Endpoint | Lists | Filters |
|---|---|---|
| `GET /api/transactions` | The customer's current and savings accounts, or one of them: deposits, withdrawals, transfers, debit card purchases and refunds, and credit card repayments from the account | `accountNumber`, `type`, `requestedCurrency`, `dateFrom`/`dateTo`, `appliedMin`/`appliedMax` |
| `GET /api/credit-cards/{cardReference}/transactions` | The whole credit account, for any period including the open cycle: purchases, refunds, repayments, and interest | `type`, `dateFrom`/`dateTo` |
| `GET /api/cards/{cardReference}/transactions` | Purchases and refunds made with one card, debit or credit. Other cards on the same account and other movements are left out | `dateFrom`/`dateTo` |
| `GET /api/credit-cards/{cardReference}/statements/{statementReference}/lines` | One closed statement, see [Credit card statements](#credit-card-statements) | none |

- Results are paginated. They are newest first by default, except statement lines, which are oldest first.
- They can be sorted by `createdAt` or `appliedAmount`, one field at a time. Rows with the same value are ordered by their ID in the same direction.
- `dateFrom` is inclusive and `dateTo` exclusive.
- A card that doesn't exist or belongs to another customer gives `404 Not Found`.

### Card spending limits

- Every card has a per-transaction limit and a daily limit, in the currency of its linked account.
- New cards get the bank's default limits for that currency. Defaults and maximums are configured per currency under `card.limits` and validated at startup.
- Customers can change both limits with `PUT /api/debit-cards/{cardReference}/spending-limits` (or `/api/credit-cards/...`), up to the bank maximum. Limits of closed or blocked cards can't be changed.
- On a credit card, spending limits are separate from the credit limit and aren't checked against it. A purchase must fit all of them.
- A purchase is checked against the amount actually debited, after any FX conversion. A purchase over either limit is rejected with `409 Conflict` and nothing is recorded.
- The day runs from midnight to midnight UTC.
- Refunds don't give back daily limit.
- Card responses include `spendingLimits` with both limits, `spentToday`, and the currency.

## Known limitations

- Only purchases paid to a merchant account inside NeoBank can be refunded through the API. In real card networks, refunds of purchases at outside merchants reach the issuing bank through the network (for example Visa or Mastercard), not from the merchant; that path is not simulated.
- Refunds are full refunds only.
- FX rate locks are kept in memory, so they are lost on restart and are not shared between application instances.
- The daily card limit resets at midnight UTC for every customer; there is no per-customer time zone.
- A purchase already in progress uses the limits it read. A limit change made at the same moment applies from the next purchase.
- A missed minimum payment has no late fee and doesn't change the account; it only leaves the grace period off.
- Interest is posted when the job closes the cycle. If the job was down, the catch-up posts it late, though the amounts are the same.
- The interest rate is the bank default per currency and can't be changed per account.
- A credit balance can only be used up by later purchases; it can't be paid out to a current account. Repaying more than the outstanding balance is rejected rather than turned into a credit balance.
- Each credit card has its own credit account. Supplementary cards on a shared credit account are not supported, and a credit account can't be closed through the API.

## Tests

The test suite includes domain and service tests, controller tests, REST-client tests, and MySQL integration tests for rollback, idempotency, and concurrent money movement, card purchases, card refunds, card spending limits, credit limits, credit card repayments, credit card statements (including concurrent closings of the same statement), and the transaction lists.

```bash
bash mvnw test
```
