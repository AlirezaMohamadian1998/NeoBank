# NeoBank

NeoBank is a work-in-progress banking backend built as a personal portfolio project with Java and Spring Boot. The project focuses on reliable money movement, ledger integrity, transaction safety, and clean domain boundaries rather than production banking integrations.

## Current features

- Customer registration and JWT-based authentication
- Current and savings accounts in TRY, USD, EUR, and GBP
- Deposits, withdrawals, and account-to-account transfers
- Double-entry ledger postings with internal settlement accounts
- Idempotent money-movement requests
- Optimistic concurrency control and transactional rollback guarantees
- Database versioning with Flyway
- Open Exchange Rates integration behind a provider abstraction
- Cached provider rates and normalized FX-rate snapshots

## Architecture

NeoBank is currently a Spring Boot monolith organized by feature.

- Operation-specific services coordinate deposits, withdrawals, and transfers.
- `LedgerPostingService` is the central boundary for balance changes and ledger-entry creation.
- A `BankTransaction` groups its ledger entries and can complete only when its postings are balanced.
- Internal settlement accounts provide the bank side of deposits and withdrawals.
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
| Accounts | `POST /api/accounts`, `GET /api/accounts` |
| Deposits | `POST /api/accounts/{accountNumber}/deposits` |
| Withdrawals | `POST /api/accounts/{accountNumber}/withdrawals` |
| Transfers | `POST /api/accounts/{accountNumber}/transfers` |
| FX rates | `GET /api/fx/rates?base=USD` |

Money-movement requests require an `Idempotency-Key` header. Protected endpoints require a bearer token.

## Tests

The test suite includes domain and service tests, controller tests, REST-client tests, and MySQL integration tests for rollback, idempotency, and concurrent money movement.

```bash
bash mvnw test
```
