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
├── rule/           FraudRule interface, RuleEngine, RuleParameters, RuleResult,
│   │               RuleEvaluationUtils (shared predicates)
│   └── impl/       8 rule implementations (VelocityRule, AmountThresholdRule, …)
├── model/          Domain value objects — Transaction (record), AlertStatus (enum),
│                   RuleName (enum, single source of truth for all rule DB keys),
│                   ChannelType (enum: ATM, POS, ONLINE, MOBILE)
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
- **Rate Limiting** — `RateLimitFilter` (servlet filter, `@Order(1)`) enforces three independent sliding-window limits using pure Java concurrency primitives (no external library): a per-IP limit of **120 req/min** on `POST /api/v1/transactions`, a strict **10 req/min** brute-force guard on `POST /api/v1/auth/token`, and a global limit of **1000 req/min** across all endpoints. Exceeded limits return `429 Too Many Requests` with a `Retry-After` header derived from the window size constant.
- **Shared Rule Utilities** — `RuleEvaluationUtils` provides stateless helper predicates (e.g. `isOffHours()`) used by multiple rules. This follows the DRY principle — the off-hours time-window check is defined once and reused by both `OffHoursRule` and `NightTimeAtmWithdrawalRule` rather than duplicated.
- **Type-Safe Enums** — `RuleName` is the single source of truth for all 8 rule identifier strings (the join key between rule implementations and the `rule_configs` database table). Each rule class delegates its `RULE_NAME` constant to `RuleName.X.name()` so a DB key typo is a compile error, not a silent skip. `ChannelType` (ATM, POS, ONLINE, MOBILE) replaces raw string comparisons and `toUpperCase()` workarounds in channel-based rules.
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

A **FraudAlert** is created when the combined risk score reaches or exceeds the configurable threshold (default: **60**; set to **20** in `application-local.yml` so that individual rules trigger fraud alerts in isolation, allowing each rule's behaviour to be verified independently).

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
 1. Authentication > Get Token                     → bearerToken auto-saved

── Baseline rules ─────────────────────────────────────────────────────────────
 2. Transactions   > Submit High Amount             → AMOUNT_THRESHOLD_RULE fires (+40), fraudulent=true
 3. Transactions   > Submit Off-Hours               → OFF_HOURS_RULE fires (+20), fraudulent=true
 4. Transactions   > Submit Multi-Rule Breach       → AMOUNT+OFF_HOURS+ROUND fires, riskScore≥85, fraudulent=true

── Capitec-realistic rules ────────────────────────────────────────────────────
 5. Transactions   > Submit Night-Time ATM          → NIGHT_TIME_ATM_RULE fires (+45) via ATM channel
 6. Transactions   > Submit POS Night-Time          → NIGHT_TIME_ATM_RULE fires (+45) via POS channel
                                                       (confirms POS is in CASH_CHANNELS)
 7. Transactions   > Submit Round-Number            → ROUND_NUMBER_AMOUNT_RULE fires (+25), fraudulent=true
 8. Transactions   > Submit Country Baseline ZAF   → Establishes ZAF history for ACC-007 (clean, score=15)
 9. Transactions   > Submit Country Mismatch        → COUNTRY_MISMATCH_RULE fires (+50), fraudulent=true
10. Transactions   > Submit Unusual Category        → UNUSUAL_MERCHANT_CATEGORY_RULE fires (+15, signal only)
11. Transactions   > Submit Clean Transaction       → fraudulent=false, no alert created
12. Transactions   > Submit Online Night (Negative) → NIGHT_TIME_ATM_RULE must NOT fire for ONLINE channel
                                                       (riskScore < 45 confirms channel isolation working)

── Alerts ─────────────────────────────────────────────────────────────────────
13. Fraud Alerts   > List All Alerts                → all fraudulent transactions visible; alertId auto-saved
14. Fraud Alerts   > Get Alert By ID                → see matchedRules array + ruleDetails JSON
15. Fraud Alerts   > Update Status (REVIEWED → CLOSED)

── Rule management ────────────────────────────────────────────────────────────
16. Rule Config    > List All Rules                 → all 8 rules, IDs, weights, parameters
17. Rule Config    > Update Velocity Rule           → live weight/threshold change, no restart
18. Rule Config    > Disable Off-Hours Rule         → test that disabled rule no longer fires
19. Rule Config    > Re-enable Off-Hours Rule

── Health ─────────────────────────────────────────────────────────────────────
20. Health         > Health Check                   → {"status":"UP"}

── Performance / SLA ──────────────────────────────────────────────────────────
21. Performance    > SLA-Health-Check               → response time < 100ms
22. Performance    > SLA-Authentication             → response time < 500ms
23. Performance    > SLA-Submit-Transaction         → response time < 500ms; 429 if rate limited
24. Performance    > SLA-List-Rules                 → response time < 300ms
25. Performance    > SLA-List-Alerts                → response time < 300ms
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

The Postman collection is in `postman/` as an alternative to Bruno.

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

Then run the application with the `local` profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or set `SPRING_PROFILES_ACTIVE=local` in your IDE run configuration.

> The `local` profile is the **only** profile and runs with **full production security** — JWT authentication is enforced. Obtain a token from `POST /api/v1/auth/token` before calling protected endpoints.

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

**175 unit tests**, **58 integration tests** — 233 total, 0 failures.

Unit tests (`src/test/java/.../unit/`) — pure JUnit 5 + Mockito, no Spring context, sub-second execution. Covers all 8 fraud rules, the rate-limit filter, the JWT provider, repository adapters, the `LogMaskUtil` POPIA masking utility, the `RuleEvaluationUtils` shared utility, both enums (`RuleName`, `ChannelType`), the `CorrelationIdFilter` (6 tests covering header propagation, UUID generation, and MDC cleanup), and the `GlobalExceptionHandler` (each HTTP status code variant). Each rule has positive, negative, boundary-condition, and rule-name identity tests. `RuleNameTest` explicitly asserts that every enum constant's `.name()` matches the corresponding rule class constant — a compile-level guard against DB key drift.

Integration tests (`*IntegrationTest`) use Testcontainers to start real PostgreSQL and Redis containers automatically. Docker must be running. Covers full end-to-end fraud evaluation for all 8 rules (positive and negative), alert lifecycle state transitions, rule configuration live updates, HTTP error handling (400/404/405/415), `CorrelationIdFilter` header propagation, `Location` header on 201 responses (RFC 7231 §6.3.2), `@Size` validation on request fields, and ChannelType isolation (POS fires NIGHT_TIME_ATM, ONLINE does not). Includes `LoadPerformanceIntegrationTest` which validates:
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

**Get a token (required — JWT is enforced):**
```bash
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

Copy the `accessToken` value and include it as `Authorization: Bearer <token>` on all subsequent requests.

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

## Spring Profiles

This service uses a **single active profile: `local`** (set in `docker-compose.yml`). The `local` profile is treated as production-equivalent — every security control that would be enforced in a live deployment is active here.

| Profile | Activated by | Swagger | Security | Threshold |
|---|---|---|---|---|
| _(base)_ | Always loaded | Off | **On** | 60 |
| `local` | `SPRING_PROFILES_ACTIVE=local` | **On** | **On** | **20** |

**Why a single profile?**
The service is deployed via Docker Compose, which sets `SPRING_PROFILES_ACTIVE=local`. A single active profile avoids ambiguity — every configuration choice in `application-local.yml` is explicit and visible. The base `application.yml` contains safe production defaults; the `local` profile overrides only what differs (score threshold, Swagger visibility).

**Why is Swagger enabled?**
Swagger UI (`http://localhost:8080/swagger-ui/index.html`) is enabled so the API can be explored and tested interactively without an external tool. The base `application.yml` has `springdoc.api-docs.enabled=false`; the `local` profile overrides it. Swagger routes are protected by `authenticated()` in `SecurityConfig` — a valid Bearer token is required.

**Production hardening active in the `local` profile:**
- `fraud.security.enabled=true` — JWT enforced on all protected endpoints
- `server.error.include-message=never` — exception detail never exposed in responses
- `spring.jpa.show-sql=false` — SQL not logged to stdout
- Rate limiting active (`transactions`: 120 req/min per IP, `auth`: 30 req/min)
- `CorrelationIdFilter` stamps every request with an `X-Capitec-Correlation-ID` for end-to-end tracing

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/frauddb` | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | `frauduser` | Database username |
| `DB_PASSWORD` | `fraudpass` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis auth password (required in non-local environments) |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | Set to `local` (done automatically by docker-compose.yml) |
| `JWT_SECRET` | _(base64 fallback)_ | HMAC signing secret — override in any real deployment |
| `JWT_EXPIRATION_MS` | `86400000` | Token lifetime in ms (24 h default) |

> **Docker Compose** sets `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` automatically — no manual env setup required for local development.

---

## Security & POPIA Compliance

The service enforces banking-grade security controls and is designed with the **Protection of Personal Information Act (POPIA)** in mind. Transaction records and fraud alerts constitute personal financial information under POPIA and are handled accordingly.

### Security Controls

| Control | Behaviour (local = production-grade) |
|---------|--------------------------------------|
| **JWT auth** | Required on all endpoints except `/api/v1/auth/token` and `/actuator/health`. Returns `401 Unauthorized` with RFC 7807 JSON body when credentials are absent. |
| **JWT secret** | Defaults to a built-in base64 key for Docker Compose convenience. Override via `JWT_SECRET` environment variable in any real deployment. |
| **Swagger / OpenAPI** | Enabled in `local` profile at `http://localhost:8080/swagger-ui/index.html`. Swagger routes require a valid Bearer token (`authenticated()` in `SecurityConfig`). |
| **Error messages** | Suppressed (`server.error.include-message: never`) — no stack traces or schema detail returned to callers. |
| **Rate limiting** | 120 req/min per IP on transactions, 30 req/min on `/auth/token`, 1000 req/min global. Exceeded limits return `429 Too Many Requests` with `Retry-After` header. |
| **Actuator** | Only `/actuator/health` exposed — `/actuator/info` disabled to prevent build metadata leakage. |
| **Correlation tracing** | Every request receives an `X-Capitec-Correlation-ID` header (echoed from the caller or auto-generated as UUID). The ID is stamped on every MDC log line for end-to-end trace correlation. |

### POPIA Controls

| Requirement | Implementation |
|-------------|----------------|
| **Log masking** | `LogMaskUtil` masks all account identifiers and transaction amounts in every log statement — no PII written to log files in plain text |
| **Data minimisation** | API responses return only fields required for fraud investigation; no unnecessary personal data is collected or stored |
| **Data retention** | Configurable via `fraud.retention.transactions-days` (365) and `fraud.retention.fraud-alerts-days` (2555 / 7 years for regulatory hold). Cleanup jobs enforce these limits in the production pipeline |
| **Access control** | All data endpoints require a valid JWT; unauthenticated requests are rejected with HTTP 401 |
| **Error sanitisation** | `GlobalExceptionHandler` maps all Spring MVC infrastructure exceptions (404, 405, 415) to their correct HTTP status codes and returns RFC 7807 `ProblemDetail` responses. The catch-all returns a generic 500 message — no internal stack traces or SQL errors ever reach the client |
| **Credential protection** | Passwords are never logged; failed auth logs only the username (required for security audit trail) |

**Getting a token:**

1. Call `POST /api/v1/auth/token` with valid credentials to get a Bearer token
2. Include `Authorization: Bearer <token>` on all subsequent API calls
3. Tokens expire after 24 hours (configurable via `fraud.security.jwt.expiration-ms`)

> **Fraud score threshold:** The `local` profile sets the fraud score threshold to **20** (base default: 60). Individual rules such as NIGHT_TIME_ATM_RULE (weight 45) or ROUND_NUMBER_AMOUNT_RULE (weight 25) trigger fraud alerts independently, allowing each rule's behaviour to be verified in isolation.

> **Auth credentials:** The `/api/v1/auth/token` endpoint validates against a BCrypt-hashed in-memory user store. Registered users: `admin`/`admin123`, `analyst`/`analyst456`, `readonly`/`readonly789`. In a deployment backed by an enterprise IdP the credential validation would be delegated there; the token-issuance contract is unchanged.

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
