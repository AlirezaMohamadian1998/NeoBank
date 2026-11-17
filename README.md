# NeoBank

NeoBank is a work-in-progress banking backend built as a personal portfolio project with Java and Spring Boot. The project focuses on reliable money movement, ledger integrity, transaction safety, and clean domain boundaries rather than production banking integrations.

## Current features

- Customer registration and JWT-based authentication
- Current and savings accounts in TRY, USD, EUR, and GBP
- Deposits, withdrawals, and account-to-account transfers, including cross-currency operations
- Short-lived, customer-scoped FX rate locks for cross-currency money movement
- Debit card issuance, retrieval, and lifecycle management (activate, freeze, block, close)
- Debit card purchases at internal merchants and external merchants through settlement accounts
- Paginated and filterable transaction history
- Double-entry ledger postings with internal settlement accounts
- Idempotent money-movement and card requests
- Optimistic concurrency control with automatic retries and transactional rollback guarantees
- Database versioning with Flyway
- Open Exchange Rates integration behind a provider abstraction, with cached and normalized rates

## Architecture

NeoBank is a Spring Boot monolith organized by feature.

- Operation-specific services coordinate deposits, withdrawals, transfers, and card purchases.
- `LedgerPostingService` is the central boundary for balance changes and ledger-entry creation.
- A `BankTransaction` groups its ledger entries and can complete only when its postings are balanced.
- Internal settlement accounts provide the bank side of deposits, withdrawals, and external card purchases.
- Idempotency records store a hash of a canonical request and a reference to the original result, so retries replay instead of repeating.
- Ledger accounts use `@Version`, and conflicting writes are retried with Spring's `@Retryable`.
- Cross-currency transactions store an FX snapshot of the rates used.
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
| Card lifecycle | `PATCH /api/debit-cards/{cardReference}/status/{status}` |
| Card purchases | `POST /api/debit-cards/{cardReference}/payments` |

Protected endpoints require a bearer token. Money-movement, card issuance, and card purchase requests require an `Idempotency-Key` header. Cross-currency deposits, withdrawals, and transfers can reference an FX rate lock, which expires after five minutes.

## Tests

The test suite includes domain and service tests, controller tests, REST-client tests, and MySQL integration tests for rollback, idempotency, and concurrent money movement and card purchases.

```bash
bash mvnw test
```
