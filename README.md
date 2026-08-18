# Fraud Rule Engine

A production-grade Spring Boot 4 microservice that evaluates configurable fraud rules against financial transaction events, flags suspicious activity, and exposes a comprehensive REST API for alert retrieval and rule management.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.0 |
| Web Layer | Spring Web (Servlet / Blocking) |
| Database | PostgreSQL 16 + Spring Data JPA + Hibernate |
| Migrations | Flyway (runs automatically on startup) |
| Velocity / Caching | Redis 7 |
| Security | Spring Security 7 + JWT (jjwt 0.12.6) |
| API Docs | SpringDoc OpenAPI 3 (Swagger UI) |
| Logging | Log4j2 (profile-aware) |
| Build | Maven 3.9 |
| Tests | JUnit 5 + Mockito + Testcontainers |
| Container | Docker + Docker Compose |

---

## Architecture

The project follows a **layered hexagonal-lite** architecture:

```
┌──────────────────────────────────────────────────────┐
│                    API Layer                          │
│  TransactionController  AlertController  RuleController│
│  AuthController         GlobalExceptionHandler        │
├──────────────────────────────────────────────────────┤
│                 Application Layer                     │
│  FraudEvaluationService   AlertQueryService           │
│  RuleConfigService                                    │
├──────────────────────────────────────────────────────┤
│                   Domain Layer                        │
│  FraudRule (interface)    RuleEngine                  │
│  VelocityRule             AmountThresholdRule         │
│  OffHoursRule             DuplicateTransactionRule    │
│  Transaction (record)     RuleResult (record)         │
├──────────────────────────────────────────────────────┤
│                Infrastructure Layer                   │
│  JPA Entities + Repositories   Redis VelocityStore   │
│  JWT Filter + Provider         Flyway Migrations      │
└──────────────────────────────────────────────────────┘
```

### Design Patterns

- **Strategy Pattern** — Each fraud rule implements the `FraudRule` interface. The `RuleEngine` iterates over all registered rules, evaluates them independently, and aggregates risk scores. New rules are added by implementing the interface and annotating with `@Component` — no engine changes required (**Open/Closed Principle**).
- **Rule Configuration** — All rule thresholds are stored in PostgreSQL (`rule_configs` table as JSONB). Thresholds can be changed at runtime via the `PATCH /api/v1/rules/{id}` endpoint without redeployment.
- **Redis Sliding Window** — The `VelocityRule` uses a Redis sorted set (`ZADD` + `ZCOUNT`) keyed by `accountId` to count transactions within a configurable time window. Expired entries are cleaned up automatically.
- **Idempotent Duplicate Detection** — `DuplicateTransactionRule` writes a fingerprint (`accountId:amount:merchant`) to Redis with a TTL, preventing the same transaction from being counted twice.

---

## Fraud Rules

| Rule | Logic | Default Threshold | Risk Score |
|---|---|---|---|
| **VELOCITY_RULE** | More than N transactions from same account in X minutes | 5 txns / 10 mins | 30 |
| **AMOUNT_THRESHOLD_RULE** | Single transaction amount exceeds limit | ZAR 10,000 | 40 |
| **OFF_HOURS_RULE** | Transaction between configurable off-hours | 00:00 – 05:00 | 20 |
| **DUPLICATE_TRANSACTION_RULE** | Same account + amount + merchant within N seconds | 60 seconds | 35 |

A **FraudAlert** is created when the combined risk score reaches or exceeds the configurable threshold (default: **60**).

All thresholds are stored in the database and can be updated live via the Rules API.

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| **Rancher Desktop** | Latest | Container runtime (includes Docker + Docker Compose) |
| **Java 25 JDK** | 25+ | Required to build the JAR |
| **Maven** | 3.9+ | Build tool |
| **Bruno** | Latest | API testing — Capitec standard (https://www.usebruno.com) |

---

## Quick Start (Docker — recommended)

> **Important:** Spring Boot 4.1.0 uses Java 25 and is built from local Maven cache. Build the JAR first, then start Docker Compose. Do **not** skip `mvn clean package`.

```bash
# 1. Clone the repository
git clone https://github.com/whenning3-hash/fraud-rule-engine.git
cd fraud-rule-engine

# 2. Build the JAR locally (uses local Maven cache, no internet download needed)
mvn clean package -DskipTests

# 3. Start Docker Compose — builds the image, starts PostgreSQL + Redis + app
#    Rancher Desktop users: Docker is already configured via the rancher-desktop context
docker compose up --build -d

# 4. Verify everything started
docker logs fraud-rule-engine
```

The application starts on **http://localhost:8080** within ~5 seconds.

Flyway runs automatically and creates all 4 tables plus seeds the 4 fraud rules.

> **Rancher Desktop users:** Docker is available at `unix://$HOME/.rd/docker.sock`. If the default
> `docker` CLI doesn't connect, prefix commands with `DOCKER_HOST=unix://$HOME/.rd/docker.sock`
> or switch context: `docker context use rancher-desktop`.

---

## Testing with Bruno (Capitec Standard)

Bruno is the industry-standard API testing tool used at Capitec. The Bruno collection is in `bruno/fraud-rule-engine/`.

**Setup (one time):**
1. Download and install Bruno: https://www.usebruno.com/downloads
2. Open Bruno → **File → Open Collection** → select the `bruno/fraud-rule-engine/` folder
3. Select the **FRE-Local** environment (top-right dropdown)

**How to test:**
1. Run **Authentication → Get Token** first — the token is automatically saved to `bearerToken`
2. Run any request in any folder — Bearer auth is configured on all protected endpoints
3. The `transactionId` and `alertId` variables auto-populate when you run the transaction requests

**Test sequence for a full fraud scenario:**
```
1. Authentication > Get Token
2. Transactions   > Submit High Amount        → fraudulent=true, riskScore=40
3. Transactions   > Submit Off-Hours          → fraudulent=true, riskScore=20
4. Transactions   > Submit Multi-Rule Breach  → fraudulent=true, riskScore=60
5. Fraud Alerts   > List All Alerts           → alertId auto-saved
6. Fraud Alerts   > Get Alert By ID           → see matched rules + rule details
7. Fraud Alerts   > Update Status → REVIEWED
8. Rule Config    > List All Rules            → 4 seeded rules
9. Rule Config    > Update Velocity Rule      → live threshold change
10. Transactions  > Submit Clean Transaction  → fraudulent=false
```

---

## Testing with Postman (Alternative)

The Postman collection is in `postman/` for reviewers who prefer Postman.

1. Open Postman → **Import** → select `postman/fraud-rule-engine.postman_collection.json`
2. Import the environment: `postman/FRE-Local.postman_environment.json`
3. Select **FRE-Local** in the environment dropdown
4. Run **🔑 Authentication → Get Token** — the token is automatically saved
5. Run requests in sequence

---

## Local Development (without Docker)

You still need PostgreSQL and Redis running locally:

```bash
# Start dependencies only (app runs from IDE or Maven)
docker compose up postgres redis -d
```

Then run the application with the `local` profile (security disabled for easy testing):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or set `SPRING_PROFILES_ACTIVE=local` in your IDE run configuration.

> The `local` profile disables JWT authentication so you can call all endpoints without a token.

---

## Building

```bash
# Compile + package (skip tests)
mvn clean package -DskipTests

# Compile + package + run all tests
mvn clean verify
```

---

## Running Tests

```bash
# Unit tests only (no infrastructure needed — fast)
mvn test

# Unit + integration tests (Testcontainers spins up real PostgreSQL + Redis)
mvn verify
```

Unit tests are in `src/test/java/.../unit/` — pure JUnit 5 + Mockito, no Spring context, sub-second execution.

Integration tests (`*IntegrationTest`) use Testcontainers to start real PostgreSQL and Redis containers automatically. Docker must be running.

---

## API Documentation

Once running, Swagger UI is available at:

**http://localhost:8080/swagger-ui/index.html**

OpenAPI JSON: **http://localhost:8080/api-docs**

---

## API Endpoints

### Authentication

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/auth/token` | Obtain a JWT Bearer token |

**Get a token (local profile: skip this, auth is disabled):**
```bash
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "demo", "password": "demo"}'
```

Use the returned token as `Authorization: Bearer <token>` on all subsequent requests.

---

### Transactions

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/transactions` | Submit a transaction for fraud evaluation |
| `GET` | `/api/v1/transactions/{id}` | Get transaction with fraud status and risk score |

**Submit a transaction:**
```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "ACC-12345",
    "amount": 15000.00,
    "currency": "ZAR",
    "merchantName": "Luxury Watches",
    "merchantCategory": "RETAIL",
    "channel": "ONLINE",
    "countryCode": "ZA",
    "transactionTime": "2025-01-15T03:30:00"
  }'
```

This transaction will trigger **AMOUNT_THRESHOLD_RULE** (R15,000 > R10,000) and **OFF_HOURS_RULE** (03:30), giving a combined score of 60 — a FraudAlert will be created.

---

### Alerts

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/alerts` | List fraud alerts (filter by accountId, status; paginated) |
| `GET` | `/api/v1/alerts/{id}` | Get alert detail including matched rule descriptions |
| `PATCH` | `/api/v1/alerts/{id}/status` | Update alert status (OPEN → REVIEWED → CLOSED) |

**List alerts:**
```bash
curl "http://localhost:8080/api/v1/alerts?accountId=ACC-12345&status=OPEN&page=0&size=10"
```

**Update alert status:**
```bash
curl -X PATCH http://localhost:8080/api/v1/alerts/{id}/status \
  -H "Content-Type: application/json" \
  -d '{"status": "REVIEWED"}'
```

---

### Rules

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/rules` | List all fraud rule configurations |
| `GET` | `/api/v1/rules/{id}` | Get a specific rule config |
| `PATCH` | `/api/v1/rules/{id}` | Update rule thresholds, enabled flag, risk weight |

**Update a rule threshold:**
```bash
curl -X PATCH http://localhost:8080/api/v1/rules/{id} \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "riskWeight": 45,
    "parameters": {
      "maxAmount": "5000.00"
    }
  }'
```

---

## Database Schema

```
transactions              fraud_alerts             rule_configs
─────────────────         ─────────────────────    ──────────────────
id (UUID PK)              id (UUID PK)             id (UUID PK)
account_id                transaction_id (FK)      rule_name (UNIQUE)
amount                    account_id               enabled
currency                  total_risk_score         risk_weight
merchant_name             status (ENUM)            parameters (JSONB)
merchant_category         rule_details (TEXT/JSON)
channel                   created_at
country_code              updated_at
transaction_time
risk_score                fraud_alert_matched_rules
is_fraudulent             ─────────────────────────
created_at                alert_id (FK)
                          rule_name
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/frauddb` | PostgreSQL connection URL |
| `SPRING_DATASOURCE_USERNAME` | `frauduser` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `fraudpass` | Database password |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | Set to `local` to disable JWT auth |
| `FRAUD_SCORE_THRESHOLD` | `60` | Minimum score to create a FraudAlert |
| `FRAUD_SECURITY_JWT_SECRET` | _(configured in yml)_ | Base64-encoded JWT secret |

---

## Security

When running with JWT enabled (all profiles except `local`):

1. Call `POST /api/v1/auth/token` with any username and password to get a Bearer token
2. Include `Authorization: Bearer <token>` on all API calls
3. Tokens expire after 24 hours (configurable via `fraud.security.jwt.expiration-ms`)

> **Note:** The `/api/v1/auth/token` endpoint accepts any credentials for demo purposes. In a real production system this would validate against a user store or OAuth2 provider (e.g. Keycloak).

---

## Architecture Decisions

**Why Spring Web (blocking) instead of WebFlux?**
The fraud evaluation involves sequential rule execution, Redis lookups, and database writes. The synchronous execution model is easier to reason about and debug for this use case, and the throughput requirements don't justify the complexity of reactive programming.

**Why is the rule engine data-driven?**
Rule thresholds are stored in PostgreSQL rather than hardcoded. This means risk teams can adjust thresholds (e.g. lower the high-value threshold during a fraud spike) without a deployment. The `PATCH /api/v1/rules/{id}` endpoint exposes this.

**Why Redis for velocity checks?**
Redis sorted sets provide O(log N) time for both inserting and range-counting by score (timestamp). A sliding window query (`ZCOUNT key windowStart now`) is a single Redis command — no full table scans.

**Why separate domain records from JPA entities?**
`Transaction` and `RuleResult` are pure Java records with no framework annotations. The rule implementations receive these clean domain objects, making them trivially testable in isolation without Spring context or mocking JPA.

---

## Troubleshooting

**Docker daemon not found (Rancher Desktop)**
```bash
# Switch to the Rancher Desktop context
docker context use rancher-desktop

# Or prefix all docker commands with:
DOCKER_HOST=unix://$HOME/.rd/docker.sock docker ...
```

**App won't start — `UnsupportedClassVersionError`**
The Docker image requires the `eclipse-temurin:25-jre-alpine` base image. Make sure you have built the JAR with Java 25 (`java -version` should show 25.x.x).

**Tables not created on startup**
Flyway migrations run on startup. If you see SQL errors, check the logs:
```bash
docker logs fraud-rule-engine 2>&1 | grep -i "error\|exception"
```
The PostgreSQL container must be healthy before the app starts — Docker Compose `depends_on: condition: service_healthy` handles this.

**Database empty after restart (volumes)**
PostgreSQL data persists in a Docker named volume (`postgres_data`). To fully reset:
```bash
docker compose down -v   # removes volumes too
mvn clean package -DskipTests
docker compose up --build -d
```

**Port already in use**
```bash
# Check what's on port 8080
lsof -i :8080
# Or change the port in docker-compose.yml: "8081:8080"
```

**Container status check**
```bash
# Status of all containers
docker compose ps

# Live app logs
docker logs -f fraud-rule-engine

# Connect to PostgreSQL directly
docker exec -it fraud-postgres psql -U frauduser -d frauddb
```
