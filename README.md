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
- Full refunds of card purchases by the merchant account's owner, at current FX rates for cross-currency purchases
- Per-transaction and daily card spending limits, with bank defaults and maximums per currency that customers can adjust
- Paginated and filterable transaction history
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
| Transaction history | `GET /api/transactions` |
| FX rate locks | `POST /api/fx/rate-locks?base=USD` |
| Debit cards | `POST /api/debit-cards/{accountNumber}`, `GET /api/debit-cards`, `GET /api/debit-cards/{cardReference}` |
| Credit cards | `POST /api/credit-cards`, `GET /api/credit-cards`, `GET /api/credit-cards/{cardReference}` |
| Card lifecycle | `PATCH /api/debit-cards/{cardReference}/status/{status}`, `PATCH /api/credit-cards/{cardReference}/status/{status}` |
| Card spending limits | `PUT /api/debit-cards/{cardReference}/spending-limits`, `PUT /api/credit-cards/{cardReference}/spending-limits` |
| Card purchases | `POST /api/cards/{cardReference}/payments` |
| Card refunds | `POST /api/accounts/{merchantAccountNumber}/card-refunds/{purchaseReference}` |
| Credit card repayments | `POST /api/credit-cards/{cardReference}/repayments` |

Protected endpoints require a bearer token. Money-movement, card issuance, card purchase, card refund, and credit card repayment requests require an `Idempotency-Key` header. Cross-currency deposits, withdrawals, transfers, and credit card repayments can reference an FX rate lock, which expires after five minutes.

Every transaction-history row includes the `transactionReference` of the movement it belongs to. Merchants use it as the `purchaseReference` when refunding, and a cardholder's refund row carries the `purchaseReference` of the purchase it reverses.

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
- Card responses include `creditLimit`, `balance` (what the customer owes), and `availableCredit` (limit minus balance).
- A purchase that would take the balance over the credit limit is rejected with `409 Conflict`. The check uses the amount actually debited, after any FX conversion.
- A refund lowers the balance. A refund after the purchase was already repaid leaves a negative balance, a credit balance: the bank owes the customer, and the available credit is higher than the limit until it is spent.
- `POST /api/credit-cards/{cardReference}/repayments` moves money from one of the customer's current accounts to the credit account. The `amount` is in the card's currency. It works whatever the card's status.
- If the current account is in another currency, the request must include an FX rate lock whose base is the card's currency, and the account is debited the converted amount. A lock sent for a same-currency repayment is rejected.
- A repayment larger than the outstanding balance is rejected with `409 Conflict`, as is one larger than the current account's balance.
- Repayments appear in the current account's transaction history as `CREDIT_CARD_REPAYMENT`.

### Transaction history

- `GET /api/transactions` returns a page of ledger entries for one of the customer's accounts (`accountNumber`), or for all of them.
- Filters: `type`, `requestedCurrency`, `dateFrom` and `dateTo`, and `appliedMin` and `appliedMax`.
- Results are newest first by default. They can be sorted by `createdAt` or `appliedAmount`, one field at a time.

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
- Credit cards have no statements, minimum payments, or interest yet.
- A credit balance can only be used up by later purchases; it can't be paid out to a current account. Repaying more than the outstanding balance is rejected rather than turned into a credit balance.
- Each credit card has its own credit account. Supplementary cards on a shared credit account are not supported, and a credit account can't be closed through the API.

## Tests

The test suite includes domain and service tests, controller tests, REST-client tests, and MySQL integration tests for rollback, idempotency, and concurrent money movement, card purchases, card refunds, card spending limits, credit limits, and credit card repayments.

```bash
bash mvnw test
```
