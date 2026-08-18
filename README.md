# Fraud Rule Engine

A production-grade Spring Boot microservice that evaluates configurable fraud rules against transaction events, flags suspicious activity, and exposes a REST API for alert retrieval and rule management.

## Architecture Overview

The project follows a **hexagonal-lite (ports and adapters) architecture** with three clear layers:

```
api/          <- REST controllers, DTOs (inbound adapters)
application/  <- Use-case services (orchestration layer)
domain/       <- Pure business logic: rules, models, interfaces
infrastructure/
  config/     <- Security, JWT, OpenAPI config
  persistence/<- JPA entities, repositories (outbound adapters)
  redis/      <- Redis velocity/duplicate store (outbound adapter)
```

### Key Design Decisions

| Decision | Rationale |
|---|---|
| Strategy Pattern for rules | Each `FraudRule` implementation is a Spring `@Component` auto-discovered and injected as `List<FraudRule>` into `RuleEngine`. Adding a new rule requires only a new class — no wiring changes. |
| DB-driven rule thresholds | Rule parameters (amounts, windows, weights) live in `rule_configs` (PostgreSQL JSONB). They can be updated at runtime via the REST API without a redeploy. |
| Redis sliding window (sorted set) | `VelocityStore` uses a Redis sorted set keyed by account ID with epoch-millisecond scores. `ZCOUNT` with a time range gives an O(log n) count of transactions in any sliding window. |
| Additive risk scoring | Each matched rule contributes its `risk_weight` to a total score. A transaction is flagged as fraudulent when `totalScore >= fraud.score.threshold` (default: 60). This avoids hard boolean rules and supports tuning. |
| Stateless JWT auth | HMAC-SHA256 JWTs; no session state. The `local` profile disables auth entirely for development. |

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker + Docker Compose

## Quick Start (Docker Compose)

```bash
git clone <repo>
cd fraud-rule-engine
docker compose up --build
```

The app, PostgreSQL, and Redis all start together. Flyway migrations run automatically on boot. The API is available at `http://localhost:8080`.

## Run Locally (without Docker for the app)

Start dependencies only:

```bash
docker compose up postgres redis -d
```

Then run the Spring Boot app:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile disables JWT authentication and enables SQL logging.

## Build

```bash
# Compile and package
mvn clean install

# Skip tests
mvn clean install -DskipTests
```

## Tests

```bash
# Unit tests only (fast, no containers)
mvn test

# All tests including integration tests (requires Docker for Testcontainers)
mvn verify
```

Integration tests use **Testcontainers** to spin up real PostgreSQL and Redis containers automatically — no manual setup required.

## API Endpoints

### Authentication

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/auth/token` | Obtain a JWT Bearer token |

```bash
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "analyst", "password": "any"}'
```

> Note: In demo mode, any username/password is accepted. Set `fraud.security.enabled=false` (or use the `local` profile) to bypass auth entirely.

### Transactions

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/transactions` | Submit a transaction for fraud evaluation |
| GET | `/api/v1/transactions/{id}` | Retrieve transaction details and fraud status |

```bash
# Submit a transaction (replace TOKEN with value from /auth/token)
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "ACC-12345",
    "amount": 15000.00,
    "currency": "ZAR",
    "merchantName": "Unknown Vendor",
    "merchantCategory": "MISC",
    "channel": "ONLINE",
    "countryCode": "ZA",
    "transactionTime": "2026-08-18T02:30:00"
  }'
```

### Alerts

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/v1/alerts` | List alerts (filter by `accountId`, `status`; paginated) |
| GET | `/api/v1/alerts/{id}` | Get alert details including matched rule breakdown |
| PATCH | `/api/v1/alerts/{id}/status` | Update alert status (`OPEN`, `REVIEWED`, `CLOSED`) |

```bash
# List open alerts
curl http://localhost:8080/api/v1/alerts?status=OPEN \
  -H "Authorization: Bearer TOKEN"

# Close an alert
curl -X PATCH http://localhost:8080/api/v1/alerts/{id}/status \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status": "CLOSED"}'
```

### Rules

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/v1/rules` | List all rule configurations |
| GET | `/api/v1/rules/{id}` | Get a specific rule configuration |
| PATCH | `/api/v1/rules/{id}` | Update rule enabled flag, weight, and parameters |

```bash
# Raise the amount threshold to 20000
curl -X PATCH http://localhost:8080/api/v1/rules/22222222-2222-2222-2222-222222222222 \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "riskWeight": 40,
    "parameters": {"maxAmount": "20000.00"}
  }'
```

## Fraud Rules

| Rule | Default Risk Weight | Description | Key Parameters |
|---|---|---|---|
| `AMOUNT_THRESHOLD_RULE` | 40 | Flags transactions above a configurable amount threshold | `maxAmount` (default: 10000.00) |
| `VELOCITY_RULE` | 30 | Flags accounts exceeding a transaction count in a sliding time window (Redis sorted set) | `maxTransactions` (default: 5), `windowMinutes` (default: 10) |
| `OFF_HOURS_RULE` | 20 | Flags transactions that occur during suspicious overnight hours | `startHour` (default: 0), `endHour` (default: 5) |
| `DUPLICATE_TRANSACTION_RULE` | 35 | Flags a repeat of the same account + amount + merchant within a short window (Redis TTL key) | `windowSeconds` (default: 60) |

A transaction is marked fraudulent when the **sum of matched rule weights** meets or exceeds `fraud.score.threshold` (default: **60**).

Examples:
- Amount > 10000 (40) + Off-hours (20) = 60 → **FRAUDULENT**
- Velocity exceeded (30) + Duplicate (35) = 65 → **FRAUDULENT**
- Amount > 10000 alone (40) = 40 → **NOT FRAUDULENT** (below threshold)

## API Documentation (Swagger UI)

Available at runtime: `http://localhost:8080/swagger-ui/index.html`

OpenAPI JSON: `http://localhost:8080/api-docs`

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `fraud.score.threshold` | `60` | Minimum total score to flag as fraudulent |
| `fraud.security.enabled` | `true` | Set to `false` to disable JWT auth |
| `fraud.security.jwt.secret` | (base64 key) | HMAC secret for JWT signing |
| `fraud.security.jwt.expiration-ms` | `86400000` | JWT validity in milliseconds (24h) |
