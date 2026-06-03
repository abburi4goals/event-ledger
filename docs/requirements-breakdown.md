# Event Ledger — Requirements Breakdown

> Derived from `Requirements/event-ledger-candidate-handout.md`
> Last updated: 2026-06-02

---

## 1. System Overview

The Event Ledger system is a **financial transaction processing pipeline** composed of two microservices. Multiple upstream systems (mainframes, batch processors, payment networks) send transaction events to this system. These upstream systems are not coordinated, so the same event may arrive more than once, and events may not arrive in the order they occurred.

```
[Upstream Systems]
  mainframe-batch ──┐
  payment-network ──┼──► POST /events ──► Event Gateway (port 8080)
  card-processor  ──┘                           │
                                                │ REST (sync, with circuit breaker)
                                                ▼
                                       Account Service (port 8081)
                                       (internal — not client-facing)
```

**Two core problems to solve:**
1. **Idempotency** — the same event must never be processed twice
2. **Out-of-order arrival** — events must be stored and listed in event-time order, not arrival order

---

## 2. Data Model

### Event (stored in Gateway DB)

| Field            | Type           | Required | Description |
|------------------|----------------|----------|-------------|
| `eventId`        | String (PK)    | Yes      | Client-assigned unique ID; used as idempotency key |
| `accountId`      | String         | Yes      | Account this event belongs to |
| `type`           | Enum           | Yes      | `CREDIT` or `DEBIT` only |
| `amount`         | BigDecimal     | Yes      | Must be > 0; never use `double`/`float` |
| `currency`       | String         | Yes      | e.g., `"USD"` |
| `eventTimestamp` | OffsetDateTime | Yes      | **When the event occurred** (client-provided, ISO 8601) |
| `receivedAt`     | OffsetDateTime | No       | **When Gateway received it** (server-set) |
| `metadata`       | JSON/String    | No       | Arbitrary key-value context (source, batchId, etc.) |

> `eventTimestamp` ≠ `receivedAt`. All sorting and business logic uses `eventTimestamp`.
> `receivedAt` is internal audit data only.

### Transaction (stored in Account Service DB)

| Field            | Type           | Description |
|------------------|----------------|-------------|
| `id`             | Long (PK)      | Surrogate primary key, auto-generated |
| `eventId`        | String (UK)    | Unique constraint — same eventId from Gateway; prevents Account Service double-apply |
| `accountId`      | String         | Links to account |
| `type`           | Enum           | `CREDIT` or `DEBIT` |
| `amount`         | BigDecimal     | Transaction amount |
| `currency`       | String         | Currency code |
| `eventTimestamp` | OffsetDateTime | Original event time |

### Account (stored in Account Service DB)

| Field     | Type        | Description |
|-----------|-------------|-------------|
| `accountId` | String (PK) | Auto-created on first transaction |
| `currency`  | String      | Account currency |

> Balance is **derived on read** (Σ CREDITs − Σ DEBITs via aggregate query over the immutable
> transactions ledger), not stored as a column. See design decision in
> `docs/design/event-processing.md` §7 and `docs/design/system-overview.md` §5.

---

## 3. Service Responsibilities

### Event Gateway (port 8080) — Public-facing

**Owns:** Event records, idempotency enforcement, input validation, routing to Account Service.

Responsibilities:
- Accept and validate incoming event payloads
- Check if `eventId` already exists → return original event if duplicate
- Generate a trace ID for every incoming request
- Call Account Service to apply the transaction (with circuit breaker)
- Store the event in its own local H2 database
- Serve event read queries from its own database (no Account Service needed)

**Does NOT own:** Account balances, transaction history, account state.

### Account Service (port 8081) — Internal only

**Owns:** Account state — balances and per-account transaction history.

Responsibilities:
- Apply transactions to accounts (create account on first transaction)
- Compute and return current balance
- Return account details with recent transactions
- Maintain its own idempotency (should not double-apply if somehow called twice with same eventId)

**Does NOT own:** Event validation, idempotency enforcement (that's the Gateway's job), external client access.

---

## 4. API Contracts

### 4.1 Event Gateway API

#### `POST /events` — Submit a transaction event

**Request body:**
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source": "mainframe-batch",
    "batchId": "B-9042"
  }
}
```

**Response matrix:**

| Scenario | Status | Body |
|---|---|---|
| New valid event, Account Service up | `201 Created` | Full event object |
| Duplicate `eventId` | `200 OK` | Original event object (identical to first 201) |
| Missing required field | `400 Bad Request` | `{ "errors": [{ "field": "amount", "message": "must be greater than 0" }] }` |
| Invalid `type` value | `400 Bad Request` | `{ "errors": [{ "field": "type", "message": "must be CREDIT or DEBIT" }] }` |
| Account Service unavailable | `503 Service Unavailable` | `{ "error": "Account Service is currently unavailable. Please retry later.", "code": "DEPENDENCY_UNAVAILABLE" }` |

**Processing flow:**
```
1. Deserialize & validate payload → 400 if invalid
2. Look up eventId in Gateway DB
   → If found: return 200 with existing event (STOP — do not call Account Service)
3. Call Account Service POST /accounts/{accountId}/transactions (with circuit breaker)
   → If circuit open or call fails: return 503
4. Persist event to Gateway DB
5. Return 201 with event object
```

---

#### `GET /events/{id}` — Retrieve a single event

**Response matrix:**

| Scenario | Status | Body |
|---|---|---|
| Event exists | `200 OK` | Full event object |
| Event not found | `404 Not Found` | `{ "error": "Event not found: evt-001" }` |

**Note:** Reads only from Gateway DB. Works even when Account Service is down.

---

#### `GET /events?account={accountId}` — List events for an account

**Response:** `200 OK` — Array of event objects sorted by `eventTimestamp` ASC.

```json
[
  { "eventId": "evt-001", "eventTimestamp": "2026-05-10T10:00:00Z", ... },
  { "eventId": "evt-003", "eventTimestamp": "2026-05-12T08:30:00Z", ... },
  { "eventId": "evt-002", "eventTimestamp": "2026-05-15T14:02:11Z", ... }
]
```

> Note: evt-002 arrived before evt-003 but has a later `eventTimestamp` — it appears last.

**Note:** Reads only from Gateway DB. Works even when Account Service is down.

---

#### `GET /health` — Gateway health check

**Response:** `200 OK`
```json
{
  "status": "UP",
  "service": "event-gateway",
  "db": "UP"
}
```
If DB is unreachable: `{ "status": "DOWN", "db": "DOWN" }`

---

### 4.2 Account Service API

#### `POST /accounts/{accountId}/transactions` — Apply a transaction

Called by Gateway only. Not exposed externally.

**Request body:**
```json
{
  "eventId": "evt-001",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z"
}
```

**Response matrix:**

| Scenario | Status | Body |
|---|---|---|
| Transaction applied | `201 Created` | `{ "accountId": "acct-123", "balance": 150.00, "currency": "USD" }` |
| Bad request (invalid data) | `400 Bad Request` | Error details |

**Account creation:** If `accountId` does not exist, create it with initial balance 0, then apply the transaction.

---

#### `GET /accounts/{accountId}/balance` — Get current balance

**Response matrix:**

| Scenario | Status | Body |
|---|---|---|
| Account exists | `200 OK` | `{ "accountId": "acct-123", "balance": 250.00, "currency": "USD" }` |
| Account not found | `404 Not Found` | `{ "error": "Account not found: acct-123" }` |

---

#### `GET /accounts/{accountId}` — Get account details

**Response:** `200 OK`. The `transactions` array is sorted by `eventTimestamp` **DESC**
(most recent first — "recent transactions"). Balance is derived (Σ CREDIT − Σ DEBIT), not stored.
```json
{
  "accountId": "acct-123",
  "balance": 250.00,
  "currency": "USD",
  "transactions": [
    { "eventId": "evt-003", "type": "CREDIT", "amount": 150.00, "eventTimestamp": "2026-05-15T14:02:11Z" },
    { "eventId": "evt-002", "type": "DEBIT",  "amount": 50.00,  "eventTimestamp": "2026-05-12T08:30:00Z" },
    { "eventId": "evt-001", "type": "CREDIT", "amount": 150.00, "eventTimestamp": "2026-05-10T10:00:00Z" }
  ]
}
```

---

#### `GET /health` — Account Service health check

Same structure as Gateway health check. `"service": "account-service"`.

---

## 5. Core Business Rules

### 5.1 Idempotency

**Rule:** The same `eventId` submitted N times must have the same effect as submitting it once.

**Implementation:**
- Gateway stores `eventId` as the primary key with a unique constraint in its DB
- On every `POST /events`, the Gateway checks: does this `eventId` already exist?
  - **Yes → return 200 OK** with the stored event. Do NOT call Account Service again.
  - **No → proceed with normal processing**
- Account Service should also protect against double-apply (store `eventId` in its transactions table with a unique constraint) as a second line of defence

**Race condition:** Two identical requests arrive simultaneously before either is stored.
- Handled by the unique constraint at the DB level — one will succeed (201), the other will catch a `DataIntegrityViolationException` and return 200.

**Status code distinction:**
- First submission → `201 Created`
- Subsequent duplicates → `200 OK` (same body, different status signals "already processed")

---

### 5.2 Out-of-Order Tolerance

**Rule:** Events must be stored and returned sorted by `eventTimestamp` (when the event happened), not by `receivedAt` (when the Gateway received it).

**Scenario illustrating the problem:**
```
T=1: Gateway receives evt-003 (eventTimestamp: 2026-05-15T15:00:00Z)
T=2: Gateway receives evt-001 (eventTimestamp: 2026-05-15T09:00:00Z)  ← arrived late
T=3: Gateway receives evt-002 (eventTimestamp: 2026-05-15T12:00:00Z)  ← arrived late

GET /events?account=acct-123 must return: [evt-001, evt-002, evt-003]
                                       (sorted by eventTimestamp, not arrival order)
```

**Balance correctness:** Because balance = Σ(CREDIT) − Σ(DEBIT), the math is commutative and associative. Order of arrival does not affect the final balance. The only requirement is that all events are included.

---

### 5.3 Balance Computation

**Formula:** `balance = Σ(amount WHERE type = CREDIT) − Σ(amount WHERE type = DEBIT)`

**Rules:**
- Always computed using `BigDecimal` arithmetic (never `double` or `float` — floating-point arithmetic is unacceptable for financial data)
- Balance can go negative (overdrafts are not in scope)
- Currency is stored per transaction; assume all transactions for one account use the same currency (not specified otherwise)

**Example:**
```
CREDIT 100.00 → balance: 100.00
DEBIT  30.00  → balance:  70.00
CREDIT 150.00 → balance: 220.00
DEBIT  80.00  → balance: 140.00
```

---

### 5.4 Input Validation

Enforced at the Gateway **before** any DB or Account Service interaction.

| Rule | HTTP Response |
|---|---|
| `eventId` is null or blank | `400` — "eventId is required" |
| `accountId` is null or blank | `400` — "accountId is required" |
| `type` is null, blank, or not `CREDIT`/`DEBIT` | `400` — "type must be CREDIT or DEBIT" |
| `amount` is null | `400` — "amount is required" |
| `amount` ≤ 0 | `400` — "amount must be greater than 0" |
| `currency` is null or blank | `400` — "currency is required" |
| `eventTimestamp` is null, blank, or not valid ISO 8601 | `400` — "eventTimestamp must be a valid ISO 8601 datetime" |

Return **all** validation errors in one response (not just the first one).

---

## 6. Non-Functional Requirements

### 6.1 Distributed Tracing

**Goal:** A single client request must be traceable across both services using a shared trace ID.

**Flow:**
```
Client → Gateway (generates traceId "abc-123")
         │  logs: traceId=abc-123 "Received POST /events"
         │
         ├─► Account Service (receives X-Trace-Id: abc-123 header)
         │      logs: traceId=abc-123 "Applying transaction"
         │
         ◄── Response propagates back
```

**Implementation (Micrometer Tracing + Spring Boot 3):**
- Spring Boot auto-configures Micrometer Tracing with `spring-boot-starter-actuator`
- Add `micrometer-tracing-bridge-otel` for OpenTelemetry bridge
- Trace ID is automatically injected into SLF4J MDC as `traceId`
- For HTTP propagation, configure `RestTemplate` with `TracingClientHttpRequestInterceptor`
  (sends the W3C `traceparent` header) **plus** a thin custom interceptor that adds an explicit
  `X-Trace-Id` header carrying the same trace id. Both headers are sent on every Gateway →
  Account Service call. See ADR-004 in `docs/design/resiliency-and-observability.md`.
- `X-Trace-Id` is the header CLAUDE.md mandates and the trace test (T-10) asserts on; `traceparent`
  carries full W3C context for any tracing backend (Jaeger/Zipkin).

**Verification:** Log output from both services for the same request must show identical trace IDs.

---

### 6.2 Structured Logging

Every log line from both services must be valid JSON with at minimum:

```json
{
  "timestamp": "2026-06-02T10:30:00.123Z",
  "level": "INFO",
  "service": "event-gateway",
  "traceId": "4bf92f3577b34da6",
  "message": "Event received",
  "eventId": "evt-001",
  "accountId": "acct-123"
}
```

**Implementation:** Logback + `logstash-logback-encoder` (produces JSON automatically from MDC).

---

### 6.3 Health Checks

Both services expose `GET /health` (not Actuator's `/actuator/health` path — a dedicated `/health` as specified):

```json
{ "status": "UP", "service": "event-gateway", "db": "UP" }
```

DB check: execute a simple query (`SELECT 1`) against H2. If it fails, report `"db": "DOWN"` and `"status": "DOWN"`.

---

### 6.4 Custom Metric

Minimum one metric must be tracked. Choose one or more:

| Option | How |
|---|---|
| Request count per endpoint | Micrometer `Counter` incremented in each controller method |
| Error rate | Counter for 4xx/5xx responses |
| Latency histogram | `@Timed` annotation on controller methods or service calls |

Expose via: Micrometer in-memory registry (logged at INFO on shutdown) or `/actuator/metrics` (if Actuator is included).

---

## 7. Resiliency: Circuit Breaker

### Pattern: Resilience4j Circuit Breaker

Wraps the `AccountServiceClient.applyTransaction()` call in the Gateway.

**Circuit breaker states:**

```
                ┌─────────────────────────────────────────┐
                │                                         │
           [failure rate                          [waitDuration
           ≥ threshold]                            expires]
                │                                         │
   CLOSED ──────┤                   OPEN ◄────────────────┘
   (normal)     ▼                   (fast-fail, return fallback)
                OPEN                     │
                        [half-open       │
                         probe calls     │
                         succeed]        │
                              ▼          │
                           HALF_OPEN ────┘
                           (test recovery)
```

**Configured values:**
| Property | Value | Reason |
|---|---|---|
| `slidingWindowSize` | 10 | Evaluate failure rate over last 10 calls |
| `failureRateThreshold` | 50 | Open if ≥50% of last 10 calls failed (i.e., 5+ failures) |
| `waitDurationInOpenState` | 30s | Stay open for 30s before attempting recovery |
| `permittedNumberOfCallsInHalfOpenState` | 3 | Allow 3 probe calls before deciding to close/reopen |
| `slowCallDurationThreshold` | 2s | Calls taking >2s count as slow |
| `slowCallRateThreshold` | 80 | Open if ≥80% of calls are slow |

**Fallback behavior:**
- When circuit is OPEN or call fails → throw `CallNotPermittedException`
- Fallback method returns a response that maps to HTTP `503 Service Unavailable`
- Response body: `{ "error": "Account Service is currently unavailable. Please retry later.", "code": "DEPENDENCY_UNAVAILABLE" }`

**What is NOT affected by the circuit breaker:**
- `GET /events/{id}` — reads from Gateway DB only
- `GET /events?account=...` — reads from Gateway DB only
- `GET /health` — local health check only

---

## 8. Graceful Degradation Scenarios

| Client Request | Account Service Status | Expected Behaviour |
|---|---|---|
| `POST /events` (new event) | UP | `201 Created` — event stored, balance updated |
| `POST /events` (new event) | DOWN | `503 Service Unavailable` — event NOT stored (client must retry) |
| `POST /events` (duplicate) | UP or DOWN | `200 OK` — return original event immediately (no Account Service call) |
| `GET /events/{id}` | UP or DOWN | `200 OK` or `404 Not Found` (Gateway DB only — unaffected) |
| `GET /events?account=X` | UP or DOWN | `200 OK` with sorted list (Gateway DB only — unaffected) |
| `GET /health` (Gateway) | UP or DOWN | `200 OK` with Gateway's own DB status |

**Key insight:** The GET endpoints on the Gateway are completely independent of Account Service availability. This is a deliberate design decision — event history is owned by the Gateway.

---

## 9. Test Requirements

### Test Inventory

| # | Category | Test Description | Tool |
|---|---|---|---|
| T-1 | Idempotency | POST same `eventId` twice → second returns `200 OK` with identical body | `@SpringBootTest` |
| T-2 | Idempotency | POST same `eventId` twice → Account Service called exactly once | WireMock verify |
| T-3 | Out-of-order | POST 3 events with non-sequential timestamps → GET returns sorted by `eventTimestamp` | `@SpringBootTest` |
| T-4 | Balance | Submit CREDIT + DEBIT in any order → balance is always CREDIT − DEBIT | `@SpringBootTest` |
| T-5 | Validation | Missing `eventId` → `400` with field error message | `@WebMvcTest` |
| T-6 | Validation | `amount = 0` and `amount = -5` → `400` | `@WebMvcTest` |
| T-7 | Validation | `type = "TRANSFER"` (unknown) → `400` | `@WebMvcTest` |
| T-8 | Circuit Breaker | Account Service returns 500 for 5+ requests → circuit opens → subsequent calls return `503` | WireMock |
| T-9 | Circuit Breaker | GET /events still works when circuit is open | WireMock |
| T-10 | Trace Propagation | POST /events → assert Account Service call received `X-Trace-Id` header | WireMock verify |
| T-11 | Integration | POST event → Account Service applies → GET balance reflects the transaction | Full stack |
| T-12 | Health | GET /health returns `{ status: UP, db: UP }` when running | `@SpringBootTest` |

---

## 10. Infrastructure Requirements

### Maven Multi-Module Structure

```
event-ledger/          ← parent POM (dependency management only)
├── event-gateway/     ← Spring Boot app, port 8080
└── account-service/   ← Spring Boot app, port 8081
```

### Docker Compose

```yaml
services:
  account-service:
    build: ./account-service
    ports: ["8081:8081"]

  event-gateway:
    build: ./event-gateway
    ports: ["8080:8080"]
    environment:
      - ACCOUNT_SERVICE_URL=http://account-service:8081
    depends_on:
      - account-service
```

Both services use H2 in-memory databases — no external DB containers needed.

---

## 11. Edge Cases & Ambiguities (with resolved decisions)

| Ambiguity | Decision |
|---|---|
| If POST /events fails after Account Service call but before Gateway save, is the event stored? | No — validate → idempotency → call Account Service → save → 201. If Account Service fails, nothing is saved. Client retries safely (idempotency handles it). |
| Can balance go negative (e.g., DEBIT before any CREDIT)? | Yes, unless specified otherwise. No overdraft protection in scope. |
| What currency does an account use if transactions use different currencies? | Assume all transactions for one account use the same currency. Not in scope to handle multi-currency. |
| Does Account Service need its own idempotency? | Yes, as a safety net — store `eventId` with a unique constraint in the transactions table. But primary idempotency enforcement is at the Gateway. |
| How does a client query balance if Account Service is internal? | Not in scope to expose a balance proxy on the Gateway (not in the spec). The graceful degradation requirement for "balance queries" refers to scenarios where someone accesses Account Service directly in development/testing. |
| What if `eventTimestamp` is far in the future or past? | No validation specified. Accept any valid ISO 8601 datetime. |
| Is pagination required for GET /events?account=X? | Not specified. Return all events for the account. |

---

## 12. Implementation Priority Order

Build in this order to maximize testability at each step:

1. **Parent POM** — set up multi-module Maven with shared dependency versions
2. **Account Service** — standalone, no external dependencies; easiest to build and verify
3. **Event Gateway** — depends on Account Service client; add circuit breaker last
4. **Tests** — unit tests alongside code; integration tests after both services exist
5. **Docker Compose** — after both services run locally
6. **README** — last

---

## 13. Bonus Items (Priority Order, Time Permitting)

| Item | Effort | Value |
|---|---|---|
| Retry with exponential backoff + jitter on Gateway → Account Service | Low | High — complements circuit breaker |
| Prometheus `/actuator/prometheus` metrics endpoint | Low | High — shows operational maturity |
| Async fallback queue (store-and-forward when Account Service is down) | High | Medium — advanced pattern, good discussion point |
| OpenTelemetry Collector + Jaeger in Docker Compose | Medium | High — visual trace demo is impressive |
| Rate limiting on Gateway | Medium | Medium |
| Contract tests (Pact) | High | Medium |
