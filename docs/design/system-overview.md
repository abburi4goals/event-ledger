# Design Document: System Overview — Event Ledger

## 1. Overview

The Event Ledger is a two-service financial transaction processing system that receives events from
multiple upstream systems (mainframes, batch processors, payment networks), enforces idempotency,
and maintains accurate account balances regardless of event arrival order. The Event Gateway is the
sole public-facing entry point; the Account Service is an internal service owned and called only by
the Gateway.

## 2. Goals

- Accept financial transaction events reliably from upstream systems that may re-send or deliver
  out of order
- Guarantee that the same event, submitted N times, has exactly the same effect as submitting it once
- Provide accurate account balances and chronologically sorted event history at any point
- Fail fast (503) on Account Service unavailability rather than hanging or corrupting state
- Keep every log line traceable across both services with a shared trace ID
- Be independently runnable: each service starts and stops without the other

## 3. Non-Goals

- Multi-currency conversion or cross-currency balance computation
- Overdraft protection or credit limits
- External client access to the Account Service (it is internal-only)
- Persistent storage beyond H2 in-memory databases (assessment scope)
- Rate limiting or authentication on the public API
- Pagination of event listings
- Asynchronous event queuing or store-and-forward (bonus item, not in base scope)

## 4. Architecture

### 4.1 System Context (C4 Level 1)

```
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │                           Event Ledger System                               │
 │                                                                             │
 │   ┌─────────────────────────┐    REST + CB    ┌───────────────────────────┐│
 │   │    Event Gateway API    │ ───────────────► │     Account Service      ││
 │   │    (port 8080)          │ ◄─────────────── │     (port 8081)          ││
 │   │    Spring Boot 3        │   sync, circuit  │     Spring Boot 3        ││
 │   │    H2 DB: events        │      breaker     │     H2 DB: accounts,     ││
 │   │    Public-facing        │                  │          transactions     ││
 │   └────────────┬────────────┘                  │     Internal only        ││
 │                ▲                               └───────────────────────────┘│
 └────────────────┼────────────────────────────────────────────────────────────┘
                  │ REST (HTTPS in prod / HTTP in assessment)
      ┌───────────┴──────────────────────┐
      │       Upstream Systems           │
      │  - mainframe-batch               │
      │  - payment-network               │
      │  - card-processor                │
      │  (may send duplicates, out-of-   │
      │   order events)                  │
      └──────────────────────────────────┘
```

### 4.2 Service Internals — Event Gateway (C4 Level 2)

```
 Event Gateway (port 8080)
 ┌──────────────────────────────────────────────────────────────────────┐
 │                                                                      │
 │  Inbound HTTP Request (POST /events, GET /events/*, GET /health)     │
 │       │                                                              │
 │       ▼                                                              │
 │  ┌───────────────────────────────────────────────┐                  │
 │  │              EventController                  │                  │
 │  │   @RestController, @Valid, Bean Validation    │                  │
 │  │   POST /events, GET /events/{id},             │                  │
 │  │   GET /events?account=, GET /health           │                  │
 │  └────────────────────┬──────────────────────────┘                  │
 │                       │  (if exception thrown anywhere below)       │
 │                       │ ─────────────────────────────────────────►  │
 │                       │         ┌─────────────────────────────┐     │
 │                       │         │    GlobalExceptionHandler   │     │
 │                       │         │    @RestControllerAdvice    │     │
 │                       │         │    intercepts exceptions,   │     │
 │                       │         │    maps to JSON error resp  │     │
 │                       │         └─────────────────────────────┘     │
 │                       │                                              │
 │                       ▼                                              │
 │  ┌───────────────────────────────────────────────┐                  │
 │  │               EventService                    │                  │
 │  │   @Service, @Transactional                    │                  │
 │  │   Idempotency check → Account Service call    │                  │
 │  │   → Gateway DB save                           │                  │
 │  └───────────┬───────────────────┬───────────────┘                  │
 │              │                   │                                   │
 │              ▼                   ▼                                   │
 │  ┌───────────────────┐   ┌─────────────────────────────────────┐    │
 │  │  EventRepository  │   │         AccountServiceClient        │    │
 │  │  Spring Data JPA  │   │   @CircuitBreaker (Resilience4j)    │    │
 │  │  findById,        │   │   applyTransaction() → HTTP POST    │    │
 │  │  findByAccountId  │   │   Fallback: ServiceUnavailableEx    │    │
 │  │  (sorted by       │   └────────────────┬────────────────────┘    │
 │  │   eventTimestamp) │                    │ RestTemplate +          │
 │  └─────────┬─────────┘                    │ TracingInterceptor      │
 │            │                              │ X-Trace-Id header       │
 │            ▼                              ▼                         │
 │  ┌─────────────────┐           Account Service :8081                │
 │  │   H2 Database   │                                                 │
 │  │   (events table)│                                                 │
 │  └─────────────────┘                                                 │
 └──────────────────────────────────────────────────────────────────────┘
```

### 4.3 Service Internals — Account Service (C4 Level 2)

```
 Account Service (port 8081)
 ┌──────────────────────────────────────────────────────────────────────┐
 │                                                                      │
 │  Inbound HTTP Request (POST /accounts/*/transactions, GET /*, etc.)  │
 │       │                                                              │
 │       ▼                                                              │
 │  ┌───────────────────────────────────────────────┐                  │
 │  │            GlobalExceptionHandler             │                  │
 │  │   @ControllerAdvice — maps exceptions to      │                  │
 │  │   structured JSON error responses             │                  │
 │  └───────────────────────────────────────────────┘                  │
 │       │                                                              │
 │       ▼                                                              │
 │  ┌───────────────────────────────────────────────┐                  │
 │  │           AccountController                   │                  │
 │  │   @RestController, @Valid                     │                  │
 │  │   POST /accounts/{id}/transactions,           │                  │
 │  │   GET /accounts/{id}/balance,                 │                  │
 │  │   GET /accounts/{id}, GET /health             │                  │
 │  └────────────────────┬──────────────────────────┘                  │
 │                       │                                              │
 │                       ▼                                              │
 │  ┌───────────────────────────────────────────────┐                  │
 │  │           AccountService (class)              │                  │
 │  │   @Service, @Transactional                    │                  │
 │  │   Auto-create account on first txn            │                  │
 │  │   Idempotency: unique constraint on eventId   │                  │
 │  │   Balance = Σ(CREDIT) − Σ(DEBIT)             │                  │
 │  └──────────┬───────────────────┬────────────────┘                  │
 │             │                   │                                   │
 │             ▼                   ▼                                   │
 │  ┌──────────────────┐   ┌──────────────────────┐                   │
 │  │ AccountRepository│   │TransactionRepository │                   │
 │  │ Spring Data JPA  │   │ Spring Data JPA       │                   │
 │  │ findById,        │   │ findByAccountId       │                   │
 │  │ save (upsert)    │   │ sumByType (balance)   │                   │
 │  └────────┬─────────┘   └──────────┬───────────┘                   │
 │           │                        │                                │
 │           ▼                        ▼                                │
 │  ┌─────────────────────────────────────────┐                        │
 │  │           H2 Database                   │                        │
 │  │   accounts table + transactions table   │                        │
 │  └─────────────────────────────────────────┘                        │
 └──────────────────────────────────────────────────────────────────────┘
```

## 5. Data Model

### Gateway — EventEntity

```
events table (Gateway H2 DB)
┌──────────────────┬──────────────────┬──────────┬──────────────────────────────────┐
│ Column           │ Java Type        │ Nullable │ Notes                            │
├──────────────────┼──────────────────┼──────────┼──────────────────────────────────┤
│ event_id         │ String           │ NOT NULL │ PK, unique constraint            │
│ account_id       │ String           │ NOT NULL │ Index for GET by account         │
│ type             │ EventType (enum) │ NOT NULL │ CREDIT or DEBIT                  │
│ amount           │ BigDecimal       │ NOT NULL │ precision=19, scale=4            │
│ currency         │ String           │ NOT NULL │ e.g. "USD"                       │
│ event_timestamp  │ OffsetDateTime   │ NOT NULL │ Client-provided, used for sort   │
│ received_at      │ OffsetDateTime   │ NOT NULL │ Server-set on ingestion          │
│ metadata         │ String (JSON)    │ NULLABLE │ Raw JSON string                  │
└──────────────────┴──────────────────┴──────────┴──────────────────────────────────┘
```

### Account Service — AccountEntity and TransactionEntity

```
accounts table (Account Service H2 DB)
┌──────────────────┬──────────────────┬──────────┬──────────────────────────────────┐
│ Column           │ Java Type        │ Nullable │ Notes                            │
├──────────────────┼──────────────────┼──────────┼──────────────────────────────────┤
│ account_id       │ String           │ NOT NULL │ PK; auto-created on first txn    │
│ currency         │ String           │ NOT NULL │ From first transaction           │
└──────────────────┴──────────────────┴──────────┴──────────────────────────────────┘

Balance is NOT a stored column. It is **derived on read** from the immutable transactions
ledger via an aggregate query: Σ(amount WHERE type=CREDIT) − Σ(amount WHERE type=DEBIT).
This is race-free and correct regardless of event arrival order (see event-processing.md
§7 "Balance Computation"). Storing a denormalized balance would risk drift and read-modify-write
races; the ledger is the single source of truth.

transactions table (Account Service H2 DB)
┌──────────────────┬──────────────────┬──────────┬──────────────────────────────────┐
│ Column           │ Java Type        │ Nullable │ Notes                            │
├──────────────────┼──────────────────┼──────────┼──────────────────────────────────┤
│ id               │ Long             │ NOT NULL │ PK (auto-generated)              │
│ event_id         │ String           │ NOT NULL │ Unique constraint (idempotency)  │
│ account_id       │ String           │ NOT NULL │ FK → accounts                    │
│ type             │ TxType (enum)    │ NOT NULL │ CREDIT or DEBIT                  │
│ amount           │ BigDecimal       │ NOT NULL │ precision=19, scale=4            │
│ currency         │ String           │ NOT NULL │ e.g. "USD"                       │
│ event_timestamp  │ OffsetDateTime   │ NOT NULL │ Original event time              │
└──────────────────┴──────────────────┴──────────┴──────────────────────────────────┘
```

## 6. API Contract

### 6.1 Event Gateway (port 8080)

#### POST /events — Submit a transaction event

Request body:
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```

Response matrix:

| Scenario                          | Status | Body                                    |
|-----------------------------------|--------|-----------------------------------------|
| New valid event, Account Svc up   | 201    | Full event object                       |
| Duplicate eventId                 | 200    | Original event object (first 201 body)  |
| Missing / invalid field           | 400    | `{"errors":[{"field":"...","message":"..."}]}` |
| Account Service unavailable       | 503    | `{"error":"Account Service is currently unavailable...","code":"DEPENDENCY_UNAVAILABLE"}` |

#### GET /events/{id}

| Scenario      | Status | Body                             |
|---------------|--------|----------------------------------|
| Event found   | 200    | Full event object                |
| Not found     | 404    | `{"error":"Event not found: id"}`|

#### GET /events?account={accountId}

Response: 200 OK — array sorted by `eventTimestamp` ASC. Empty array if no events.
Reads Gateway DB only. Works when Account Service is down.

#### GET /health (Gateway)

```json
{ "status": "UP", "service": "event-gateway", "db": "UP" }
```
If H2 unreachable: `{ "status": "DOWN", "service": "event-gateway", "db": "DOWN" }`

---

### 6.2 Account Service (port 8081)

#### POST /accounts/{accountId}/transactions — Apply a transaction

Request body:
```json
{
  "eventId": "evt-001",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z"
}
```

| Scenario               | Status | Body                                            |
|------------------------|--------|-------------------------------------------------|
| Applied                | 201    | `{"accountId":"...","balance":150.00,"currency":"USD"}` |
| Invalid data           | 400    | Error details                                   |
| Duplicate eventId      | 200    | Existing balance (no double-apply)              |

Account is auto-created on first transaction with balance 0.

#### GET /accounts/{accountId}/balance

| Scenario          | Status | Body                                                   |
|-------------------|--------|--------------------------------------------------------|
| Account exists    | 200    | `{"accountId":"acct-123","balance":250.00,"currency":"USD"}` |
| Not found         | 404    | `{"error":"Account not found: acct-123"}`              |

#### GET /accounts/{accountId}

Response: 200 OK. The `transactions` array is the account's recent history, sorted by
`eventTimestamp` **DESC** (most recent first — "recent transactions" per the handout). The
balance is derived (Σ CREDIT − Σ DEBIT), not read from a stored column. Returns 404 if the
account does not exist.
```json
{
  "accountId": "acct-123",
  "balance": 250.00,
  "currency": "USD",
  "transactions": [
    { "eventId": "evt-002", "type": "DEBIT",  "amount": 50.00,  "eventTimestamp": "2026-05-12T08:30:00Z" },
    { "eventId": "evt-001", "type": "CREDIT", "amount": 150.00, "eventTimestamp": "2026-05-10T10:00:00Z" }
  ]
}
```

#### GET /health (Account Service)

```json
{ "status": "UP", "service": "account-service", "db": "UP" }
```

## 7. Key Design Decisions

---

### ADR-001: Why Resilience4j Circuit Breaker (over retry, over timeout-only)

#### Context

The Gateway must call the Account Service for every new POST /events. The Account Service could
be slow, crash, or become temporarily unavailable. We need a resiliency pattern to protect the
Gateway and its clients from cascading failure.

Three patterns were evaluated: circuit breaker, retry with backoff, and timeout-only.

#### Decision

Use Resilience4j `@CircuitBreaker` wrapping `AccountServiceClient.applyTransaction()`. Fallback
returns a `ServiceUnavailableException` that maps to HTTP 503.

#### Consequences

##### Positive
- Fast-fail behavior: when the Account Service is degraded, Gateway clients receive 503 immediately
  rather than waiting for each request to time out
- Automatic recovery: HALF_OPEN state probes Account Service after 30s; circuit closes if healthy
- Prevents load amplification: a broken Account Service does not receive a storm of retried requests
- Observable state: Resilience4j exposes circuit state via Micrometer metrics / actuator
- Clear client contract: 503 with `DEPENDENCY_UNAVAILABLE` code signals "retry later"

##### Negative
- New POST /events returns 503 (not 201) when circuit is open — clients must implement retry logic
- Events are NOT persisted when circuit is open (atomic: Account Service call must succeed before
  Gateway saves the event — this keeps both services consistent)
- 30s window before recovery probe means legitimate traffic is blocked even after Account Service
  comes back, until the wait duration expires

##### Alternatives Considered
- **Retry with exponential backoff only**: Appropriate for transient network blips. Not appropriate
  when Account Service is systemically failing — retrying amplifies load and hangs request threads.
  Rejected as primary pattern; can be added as a layer below the circuit breaker (bonus item).
- **Timeout-only**: Prevents indefinite hangs but does not stop retrying a failing service.
  Every request still attempts the call and waits for the timeout to expire (e.g., 5s × N requests
  = N×5s of blocked threads). Rejected as sole pattern.
- **Bulkhead**: Isolates thread pool; complementary to circuit breaker but does not provide
  fast-fail semantics. Could be added alongside circuit breaker in a production system.

#### Status
Accepted

#### Date
2026-06-02

---

### ADR-002: Why Synchronous REST (over async messaging)

#### Context

The Gateway must notify the Account Service of each transaction. Two architectural options exist:
synchronous REST (request-response) or asynchronous messaging (Kafka, SQS, RabbitMQ).

#### Decision

Use synchronous REST. Gateway calls `POST /accounts/{accountId}/transactions` and waits for a 201
response before persisting the event and returning 201 to the client.

#### Consequences

##### Positive
- Matches the requirements specification explicitly: "Synchronous REST calls between services"
- Simple operational model: no broker infrastructure, no consumer group management, no DLQ
- Immediate consistency: when Gateway returns 201, both services are already in sync
- Easy to test: WireMock can stub the Account Service directly
- Circuit breaker provides the resiliency benefit that async would otherwise give "for free"

##### Negative
- Tight availability coupling: if Account Service is down, POST /events fails (503). With async
  messaging, events could be queued and processed when Account Service recovers.
- Higher latency per request: client waits for the full Gateway → Account Service round-trip
- No built-in replay: failed events must be retried by the client (idempotency makes this safe)

##### Alternatives Considered
- **Async messaging (Kafka/SQS)**: Decouples availability — Gateway can accept events even when
  Account Service is down, process from queue when it recovers. Adds significant operational
  complexity (broker, consumer groups, DLQ, ordering guarantees). Rejected for assessment scope;
  noted as bonus async fallback queue item.
- **Outbox pattern**: Gateway writes event to its DB and a separate outbox table; a relay process
  publishes to Account Service. Provides durability without a broker. Too complex for a 3-4h
  assessment. Rejected.

#### Status
Accepted

#### Date
2026-06-02

---

### ADR-003: Why H2 In-Memory Database (scoped to assessment)

#### Context

Each service needs its own database. The assessment requires embedded/in-memory storage. The
choice is between H2 (in-memory), H2 (file-based), SQLite, or a real RDBMS (PostgreSQL).

#### Decision

Use H2 in-memory mode (`spring.datasource.url=jdbc:h2:mem:...`) for both services. Each service
has a completely independent H2 instance with no shared state.

#### Consequences

##### Positive
- Zero setup: H2 ships with Spring Boot; no external DB process needed
- Fast test execution: in-memory DB starts in milliseconds; JPA schema is auto-created from entities
- Reinforces service isolation: two services, two separate H2 instances — impossible to share data
  by accident
- Full SQL semantics: H2 supports standard SQL, transactions, unique constraints, and foreign keys
- Docker Compose requires no DB container — simpler deployment for the assessment

##### Negative
- All data is lost on restart — not appropriate for production
- H2 SQL dialect differs slightly from production RDBMSes (PostgreSQL, MySQL); some queries need
  adaptation when migrating
- In-memory H2 is not safe for multi-instance (horizontal scaling) — fine for single-instance
  assessment but a hard blocker for production

##### Alternatives Considered
- **H2 file-based**: Persists data across restarts. Adds path configuration complexity; not needed
  for an assessment where data does not need to survive restarts. Rejected.
- **PostgreSQL via Docker Compose**: Production-realistic but adds DB containers, connection
  configuration, and migration tooling. Exceeds the assessment time box. Rejected.
- **SQLite**: Lighter than H2 but not as well-integrated with Spring Data JPA / Hibernate.
  H2 is the de-facto standard for Spring Boot testing. Rejected.

#### Status
Accepted

#### Date
2026-06-02

## 8. Error Handling Strategy

All errors are handled by a `@ControllerAdvice GlobalExceptionHandler` in each service.

```
Exception type                    → HTTP Status  → Response body
─────────────────────────────────────────────────────────────────────────────
MethodArgumentNotValidException   → 400          → {"errors":[{field, message}]}
ConstraintViolationException      → 400          → {"errors":[{field, message}]}
HttpMessageNotReadableException   → 400          → if caused by InvalidFormatException on an enum
  (cause: InvalidFormatException)                  ("type") or OffsetDateTime ("eventTimestamp"):
                                                   {"errors":[{field, message}]} (uniform shape);
                                                   otherwise {"error":"Malformed request body"}
EventNotFoundException            → 404          → {"error":"Event not found: id"}
AccountNotFoundException          → 404          → {"error":"Account not found: id"}
ServiceUnavailableException       → 503          → {"error":"...","code":"DEPENDENCY_UNAVAILABLE"}
DataIntegrityViolationException   → 200          → existing event (idempotency race fallback)
Exception (catch-all)             → 500          → {"error":"Internal server error"}
```

Rules enforced in all handlers:
- Never expose stack traces or internal exception messages to clients
- Log exceptions at WARN (expected errors like 404) or ERROR (unexpected 5xx) with traceId in MDC
- Return all validation errors in one response (not first-error-only)

## 9. Observability

### Distributed Tracing

- Micrometer Tracing (`micrometer-tracing-bridge-otel`) auto-generates trace IDs per request
- Spring Boot 3 auto-wires MDC injection: every log line includes `traceId` from SLF4J MDC
- `RestTemplate` is configured with `TracingClientHttpRequestInterceptor` to propagate the W3C
  `traceparent` header, plus a thin custom interceptor that adds an explicit `X-Trace-Id` header
  (the header CLAUDE.md mandates and T-10 asserts) carrying the same trace id — both flow from
  Gateway to Account Service. See ADR-004 in resiliency-and-observability.md for the rationale.
- Both services log `traceId` on every line without manual extraction

### Structured Logging

Every log line is valid JSON via `logstash-logback-encoder`:
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

### Metrics

Micrometer auto-instruments Spring MVC:
- `http.server.requests` counter/timer by URI, method, status
- Circuit breaker state exposed via `resilience4j.circuitbreaker.*` metrics
- Custom counter: `events.received.total` (tagged by type: CREDIT/DEBIT, duplicate: true/false)
- Exposed via `/actuator/metrics` (and `/actuator/prometheus` as bonus)

### Health

- `GET /health` on both services — custom controller (not Actuator path)
- Performs a `SELECT 1` against H2 to verify DB connectivity
- Returns `{"status":"UP","service":"...","db":"UP"}` or DOWN equivalents

## 10. Testing Strategy

| Test ID | Category         | Tool                      | What It Verifies                              |
|---------|------------------|---------------------------|-----------------------------------------------|
| T-1     | Idempotency      | @SpringBootTest + WireMock| Second POST returns 200, same body            |
| T-2     | Idempotency      | WireMock verify           | Account Service called exactly once           |
| T-3     | Out-of-order     | @SpringBootTest           | GET events sorted by eventTimestamp ASC       |
| T-4     | Balance          | @SpringBootTest           | CREDIT − DEBIT correct in any arrival order   |
| T-5     | Validation       | @WebMvcTest               | Missing fields → 400 with field error message |
| T-6     | Validation       | @WebMvcTest               | amount <= 0 → 400                            |
| T-7     | Validation       | @WebMvcTest               | Unknown type → 400                           |
| T-8     | Circuit Breaker  | WireMock                  | 5+ failures → circuit opens → 503            |
| T-9     | Circuit Breaker  | WireMock                  | GET /events works when circuit is open        |
| T-10    | Trace Propagation| WireMock verify           | X-Trace-Id header sent to Account Service     |
| T-11    | Integration      | @SpringBootTest full stack| POST → Account Svc → GET balance reflects txn |
| T-12    | Health           | @SpringBootTest           | GET /health returns UP when running           |

## 11. Open Questions

- Should the Gateway proxy balance queries from Account Service to external clients?
  The handout's graceful-degradation section lists "Balance queries — return a clear error when
  Account Service is unreachable." The Gateway exposes NO balance endpoint (balance is owned by the
  Account Service, which is internal), so there is no Gateway path to degrade. Resolution: the
  requirement is satisfied at the Account Service boundary — a direct balance query to a down
  Account Service yields a connection error, and were the Gateway ever to proxy balances it would
  reuse the same circuit breaker + 503 fallback as POST /events. This is an explicit, accepted
  scoping decision (the spec does not require a Gateway balance proxy), not an oversight. If a
  reviewer expects a Gateway balance proxy, it is a ~30-minute add: a GET /accounts/{id}/balance
  proxy guarded by the existing `accountService` circuit breaker, returning 503 when OPEN.
- What happens if Account Service returns 200 but Gateway DB save fails?
  The event is not stored; client retries (idempotency key not saved, so retry is treated as new).
  This is the correct behavior — Account Service deduplication via unique constraint prevents
  double-apply on the Account Service side.
- If bonus async fallback is implemented, should queued events count toward the idempotency check?
  Yes — queue entry should use eventId as deduplication key. Not in base scope.
- Prometheus scraping interval and retention — out of scope for assessment; default Micrometer
  in-memory registry sufficient.
