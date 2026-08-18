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

The project uses a flat, industry-standard Spring Boot package layout:

```
za.co.fraudruleengine
├── api/            Controllers (TransactionController, AlertController,
│   └── dto/        RuleController, AuthController) + request/response DTOs
├── service/        Business logic (FraudEvaluationService, AlertQueryService,
│                   RuleConfigService)
├── rule/           FraudRule interface, RuleEngine, RuleParameters, RuleResult
│   └── impl/       8 rule implementations (VelocityRule, AmountThresholdRule, …)
├── model/          Domain value objects — Transaction (record), AlertStatus (enum)
├── entity/         JPA entities — TransactionEntity, FraudAlertEntity, RuleConfigEntity
├── repository/     Spring Data JPA repos + TransactionHistoryAdapter
├── redis/          VelocityStorePort (interface) + VelocityStore (Redis impl)
├── config/         Spring Security, JWT filter/provider, Flyway, Jackson, OpenAPI
└── filter/         RateLimitFilter (servlet filter, @Order(1))
```

**Request flow:**
```
HTTP request
  → RateLimitFilter          (429 if rate exceeded)
  → JwtAuthenticationFilter  (401 if token invalid)
  → Controller               (validates + maps DTO)
  → Service                  (orchestrates evaluation)
  → RuleEngine               (iterates all 8 rules, aggregates score)
  → Repository / Redis       (persists alert if score ≥ threshold)
```

### Design Patterns

- **Strategy Pattern** — Each fraud rule implements the `FraudRule` interface. The `RuleEngine` iterates over all registered rules, evaluates them independently, and aggregates risk scores. New rules are added by implementing the interface and annotating with `@Component` — no engine changes required (**Open/Closed Principle**).
- **Rule Configuration** — All rule thresholds are stored in PostgreSQL (`rule_configs` table as JSONB). Thresholds can be changed at runtime via the `PATCH /api/v1/rules/{id}` endpoint without redeployment.
- **Redis Sliding Window** — The `VelocityRule` uses a Redis sorted set (`ZADD` + `ZCOUNT`) keyed by `accountId` to count transactions within a configurable time window. Expired entries are cleaned up automatically.
- **Idempotent Duplicate Detection** — `DuplicateTransactionRule` writes a fingerprint (`accountId:amount:merchant`) to Redis with a TTL, preventing the same transaction from being counted twice.
- **Rate Limiting** — `RateLimitFilter` (servlet filter, `@Order(1)`) enforces two independent sliding-window limits using pure Java concurrency primitives (no external library): a per-IP limit of **120 req/min** on `POST /api/v1/transactions` and a global limit of **1000 req/min** across all endpoints. Exceeded limits return `429 Too Many Requests` with a `Retry-After: 60` header.
- **Connection Pool Tuning** — HikariCP is configured with `maximum-pool-size=20`, `minimum-idle=5`, and `keepalive-time=60s`. Lettuce (Redis client) connection pool is enabled with `max-active=16`. Tomcat thread pool is set to `max=200` with `accept-count=100`. All settings are externalised in `application.yml` for environment-specific tuning.

---

## Fraud Rules

| Rule | Logic | Default Threshold | Risk Weight |
|---|---|---|---|
| **VELOCITY_RULE** | More than N transactions from same account in X minutes | 5 txns / 10 mins | 30 |
| **AMOUNT_THRESHOLD_RULE** | Single transaction amount exceeds limit | ZAR 10,000 (strict greater-than) | 40 |
| **OFF_HOURS_RULE** | Transaction between configurable off-hours | 00:00 – 05:00 | 20 |
| **DUPLICATE_TRANSACTION_RULE** | Same account + amount + merchant within N seconds | 60 seconds | 35 |
| **ROUND_NUMBER_AMOUNT_RULE** | Amount ≥ R5,000 AND exactly divisible by R1,000 (structuring) | R5,000 min, R1,000 divisor | 25 |
| **NIGHT_TIME_ATM_RULE** | Channel is ATM/POS + off-hours (00:00–05:00) + amount ≥ R1,500 | All three must be true | 45 |
| **COUNTRY_MISMATCH_RULE** | Current country differs from any country in the last 24 h | Different ISO 3166-1 country | 50 |
| **UNUSUAL_MERCHANT_CATEGORY_RULE** | First-ever transaction in a given merchant category for the account | No prior history for that category | 15 |

A **FraudAlert** is created when the combined risk score reaches or exceeds the configurable threshold (default: **60** in production; **20** on the `local` profile to allow individual rules to trigger alerts during demos).

All thresholds and risk weights are stored in the database and can be updated live via the Rules API — no redeployment needed.

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

Flyway runs automatically and creates all required tables and seeds **8 fraud rules** (4 baseline + 4 Capitec-realistic patterns).

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
── Authentication ─────────────────────────────────────────────────────────────
 1. Authentication > Get Token               → bearerToken auto-saved

── Baseline rules ─────────────────────────────────────────────────────────────
 2. Transactions   > Submit High Amount      → AMOUNT_THRESHOLD_RULE fires (+40), fraudulent=true
 3. Transactions   > Submit Off-Hours        → OFF_HOURS_RULE fires (+20), fraudulent=true
 4. Transactions   > Submit Multi-Rule Breach→ AMOUNT+OFF_HOURS+ROUND fires, riskScore≥85, fraudulent=true

── Capitec-realistic rules ────────────────────────────────────────────────────
 5. Transactions   > Submit Night-Time ATM   → NIGHT_TIME_ATM_RULE fires (+45), fraudulent=true
 6. Transactions   > Submit Round-Number     → ROUND_NUMBER_AMOUNT_RULE fires (+25), fraudulent=true
 7. Transactions   > Submit Country Baseline ZAF → Establishes ZAF history for ACC-007 (clean, score=15)
 8. Transactions   > Submit Country Mismatch    → COUNTRY_MISMATCH_RULE fires (+50), fraudulent=true
 9. Transactions   > Submit Unusual Category → UNUSUAL_MERCHANT_CATEGORY_RULE fires (+15, signal only)

── Alerts ─────────────────────────────────────────────────────────────────────
10. Fraud Alerts   > List All Alerts         → all fraudulent transactions visible; alertId auto-saved
11. Fraud Alerts   > Get Alert By ID         → see matchedRules array + ruleDetails JSON
12. Fraud Alerts   > Update Status (REVIEWED → CLOSED)

── Rule management ────────────────────────────────────────────────────────────
13. Rule Config    > List All Rules          → all 8 rules, IDs, weights, parameters
14. Rule Config    > Update Velocity Rule    → live weight/threshold change, no restart
15. Rule Config    > Disable Off-Hours Rule  → test that disabled rule no longer fires
16. Rule Config    > Re-enable Off-Hours Rule

── Verification ───────────────────────────────────────────────────────────────
17. Transactions   > Submit Clean Transaction→ fraudulent=false, no alert created
18. Health         > Health Check            → {"status":"UP"}

── Performance / SLA ──────────────────────────────────────────────────────────
19. Performance    > SLA-Health-Check        → response time < 100ms
20. Performance    > SLA-Authentication      → response time < 500ms
21. Performance    > SLA-Submit-Transaction  → response time < 500ms; 429 if rate limited
22. Performance    > SLA-List-Rules          → response time < 300ms
23. Performance    > SLA-List-Alerts         → response time < 300ms
```

**Run a Bruno load test (Bruno CLI):**
```bash
# Install once
npm install -g @usebruno/cli

# 100 sequential transaction evaluations — verify all respond within SLA
bru run Performance/SLA-Submit-Transaction.bru \
    --env FRE-Local \
    --iteration-count 100 \
    --delay 200

# Faster burst (no delay) — verifies app handles back-pressure gracefully
# Requests that exceed the per-IP rate limit (120/min) return 429, which the test accepts.
bru run Performance/SLA-Submit-Transaction.bru \
    --env FRE-Local \
    --iteration-count 50
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

**129 unit tests**, **43 integration tests** — 172 total.

Unit tests (`src/test/java/.../unit/`) — pure JUnit 5 + Mockito, no Spring context, sub-second execution. Covers all 8 fraud rules, the rate-limit filter, the JWT provider, repository adapters, and the `LogMaskUtil` POPIA masking utility. Each rule has positive, negative, and boundary-condition tests.

Integration tests (`*IntegrationTest`) use Testcontainers to start real PostgreSQL and Redis containers automatically. Docker must be running. Includes `LoadPerformanceIntegrationTest` which validates:
- 20 concurrent requests complete within a 10-second wall-clock budget (virtual threads)
- High-risk and low-risk transactions are scored independently with no state bleed-through
- Rate-limit filter returns `429 Too Many Requests` when the per-IP threshold is exceeded

---

## API Documentation

Swagger UI is enabled on the `local` profile only (disabled in production to prevent API enumeration).

Once running locally, Swagger UI is available at:

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
  -d '{"username": "admin", "password": "admin123"}'
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
    "countryCode": "ZAF",
    "transactionTime": "2025-01-15T03:30:00"
  }'
```

This transaction will trigger **AMOUNT_THRESHOLD_RULE** (R15,000 > R10,000) and **OFF_HOURS_RULE** (03:30), giving a combined score of 60 — a FraudAlert will be created.

> **Country code format:** Use ISO 3166-1 alpha-3 three-letter codes: `ZAF` (South Africa), `GBR` (United Kingdom), `USA` (United States), `NAM` (Namibia), `MOZ` (Mozambique). The `countryCode` field is optional — if omitted, the country-mismatch rule is skipped for that transaction.

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

**Seeded rule IDs** (pre-populated by Flyway migrations V4 + V5):

| Rule Name | UUID |
|---|---|
| `VELOCITY_RULE` | `11111111-1111-1111-1111-111111111111` |
| `AMOUNT_THRESHOLD_RULE` | `22222222-2222-2222-2222-222222222222` |
| `OFF_HOURS_RULE` | `33333333-3333-3333-3333-333333333333` |
| `DUPLICATE_TRANSACTION_RULE` | `44444444-4444-4444-4444-444444444444` |
| `ROUND_NUMBER_AMOUNT_RULE` | `55555555-5555-5555-5555-555555555555` |
| `NIGHT_TIME_ATM_RULE` | `66666666-6666-6666-6666-666666666666` |
| `COUNTRY_MISMATCH_RULE` | `77777777-7777-7777-7777-777777777777` |
| `UNUSUAL_MERCHANT_CATEGORY_RULE` | `88888888-8888-8888-8888-888888888888` |

These IDs are stable — they are seeded by Flyway and never auto-generated, so the same IDs work across all environments. They are also pre-loaded as environment variables in the Bruno collection (`FRE-Local` environment).

**Update a rule threshold:**
```bash
curl -X PATCH http://localhost:8080/api/v1/rules/22222222-2222-2222-2222-222222222222 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
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
| `JWT_SECRET` | _(fallback in yml — override in prod)_ | Base64-encoded HS256 signing secret — **must** be set via env var in every non-local deployment |

---

## Security & POPIA Compliance

The service enforces banking-grade security controls and is designed with the **Protection of Personal Information Act (POPIA)** in mind. Transaction records and fraud alerts constitute personal financial information under POPIA and are handled accordingly.

### Security Controls

| Control | Production behaviour | Local override |
|---------|---------------------|----------------|
| **JWT auth** | Required on all endpoints except `/api/v1/auth/token` and `/actuator/health` | Disabled (`fraud.security.enabled: false`) |
| **JWT secret** | Injected from `JWT_SECRET` environment variable — never stored in source | Falls back to default in yml |
| **Swagger / OpenAPI** | Disabled (`springdoc.api-docs.enabled: false`) — API docs must not be publicly accessible | Enabled via `application-local.yml` |
| **Error messages** | Suppressed (`server.error.include-message: never`) — prevents schema/stack-trace leakage | `always` in local profile |
| **Rate limiting** | 120 req/min per IP + 1000 req/min global; `trust-proxy-headers: false` prevents IP spoofing | Same |
| **Actuator** | Only `/actuator/health` exposed — `/info` disabled to prevent metadata leakage | Same |

### POPIA Controls

| Requirement | Implementation |
|-------------|----------------|
| **Log masking** | `LogMaskUtil` masks all account identifiers and transaction amounts in every log statement — no PII written to log files in plain text |
| **Data minimisation** | API responses return only fields required for fraud investigation; no unnecessary personal data is collected or stored |
| **Data retention** | Configurable via `fraud.retention.transactions-days` (365) and `fraud.retention.fraud-alerts-days` (2555 / 7 years for regulatory hold). Cleanup jobs enforce these limits in the production pipeline |
| **Access control** | All data endpoints require a valid JWT; unauthenticated requests are rejected with HTTP 401 |
| **Error sanitisation** | The `GlobalExceptionHandler` catch-all returns a generic message — internal stack traces and SQL errors never reach the client |
| **Credential protection** | Passwords are never logged; failed auth logs only the username (required for security audit trail) |

**Getting a token (all non-local profiles):**

1. Call `POST /api/v1/auth/token` with any username and password to get a Bearer token
2. Include `Authorization: Bearer <token>` on all subsequent API calls
3. Tokens expire after 24 hours (configurable via `fraud.security.jwt.expiration-ms`)

> **Local profile fraud threshold:** The `local` profile lowers the fraud score threshold from **60** (production) to **20**. This means individual rules (e.g. NIGHT_TIME_ATM_RULE with weight 45, or ROUND_NUMBER_AMOUNT_RULE with weight 25) will trigger fraud alerts on their own, making it easy to demonstrate each rule independently in Bruno or Postman.

> **Note:** The `/api/v1/auth/token` endpoint accepts any credentials for demo purposes. In a real production system this would validate against a user store or OAuth2 provider (e.g. Keycloak).

---

## Architecture Decisions

**Why Spring Web (blocking) instead of WebFlux?**
The fraud evaluation involves sequential rule execution, Redis lookups, and database writes. The synchronous execution model is easier to reason about and debug for this use case, and the throughput requirements don't justify the complexity of reactive programming.

**Why is the rule engine data-driven?**
Rule thresholds are stored in PostgreSQL rather than hardcoded. This means risk teams can adjust thresholds (e.g. lower the high-value threshold during a fraud spike) without a deployment. The `PATCH /api/v1/rules/{id}` endpoint exposes this.

**Why Redis for velocity checks?**
Redis sorted sets provide O(log N) time for both inserting and range-counting by score (timestamp). A sliding window query (`ZCOUNT key windowStart now`) is a single Redis command — no full table scans.

**Why separate model records from JPA entities?**
`Transaction` and `RuleResult` (in `model/` and `rule/`) are pure Java records with no framework annotations. The rule implementations receive these clean objects, making them trivially testable in isolation without a Spring context or JPA mocking.

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
