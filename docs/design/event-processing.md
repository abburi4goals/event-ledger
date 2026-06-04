# Design Document: Event Processing — Core Flow

## 1. Overview

This document describes the core event processing pipeline: how an event enters the system at the
Event Gateway, how idempotency is enforced, how out-of-order events are tolerated, and how the
Gateway coordinates with the Account Service to maintain account state. The design ensures that
every event is processed exactly once and event history is always sorted by the time the event
occurred — not the time it arrived.

## 2. Goals

- Process each unique event exactly once, regardless of how many times it is submitted
- Produce a correct account balance (Σ CREDITs − Σ DEBITs) that is independent of arrival order
- Return event listings sorted by event occurrence time (eventTimestamp), not ingestion time
- Queue events locally when Account Service is unavailable and forward them when it recovers
- Return all validation errors in a single 400 response (not first-error-only)
- Handle concurrent duplicate submissions atomically via database constraint

## 3. Non-Goals

- Ordering guarantees at the Account Service level (balance math is commutative)
- Retroactive correction of already-processed events
- Pagination of GET /events?account= results
- Multi-currency conversion or balance in a normalized currency
- Overdraft protection or business rules on balance limits
- Processing events submitted with future or far-past timestamps (accepted as-is)

## 4. Architecture

### 4.1 Sequence: POST /events — Happy Path (new event, Account Service up)

```
Client          Gateway          Gateway DB       Account Service    Account DB
  │                │                  │                  │                │
  │  POST /events  │                  │                  │                │
  │  ─────────────►│                  │                  │                │
  │                │                  │                  │                │
  │                │  [1] Validate    │                  │                │
  │                │  Bean Validation │                  │                │
  │                │  (all fields)    │                  │                │
  │                │                  │                  │                │
  │                │  [2] findById    │                  │                │
  │                │  (eventId)       │                  │                │
  │                │  ───────────────►│                  │                │
  │                │  null (new)      │                  │                │
  │                │  ◄───────────────│                  │                │
  │                │                  │                  │                │
  │                │  [3] POST /accounts/{accountId}/transactions         │
  │                │  (via AccountServiceClient + CircuitBreaker)         │
  │                │  ──────────────────────────────────►│                │
  │                │                  │                  │  [3a] findOrCreate
  │                │                  │                  │  account       │
  │                │                  │                  │  ─────────────►│
  │                │                  │                  │                │
  │                │                  │                  │  [3b] save txn │
  │                │                  │                  │  ─────────────►│
  │                │                  │                  │                │
  │                │                  │                  │  [3c] update   │
  │                │                  │                  │  balance       │
  │                │                  │                  │  ─────────────►│
  │                │  201 Created     │                  │                │
  │                │  ◄──────────────────────────────────│                │
  │                │                  │                  │                │
  │                │  [4] save event  │                  │                │
  │                │  ───────────────►│                  │                │
  │                │  saved           │                  │                │
  │                │  ◄───────────────│                  │                │
  │                │                  │                  │                │
  │  201 Created   │                  │                  │                │
  │  ◄─────────────│                  │                  │                │
```

**Key ordering invariant:** Account Service is called BEFORE the event is saved to Gateway DB.
If the Account Service call fails, nothing is persisted. The client retries safely: the idempotency
key was never stored, so the retry is treated as a new event.

---

### 4.2 Sequence: POST /events — Duplicate Event (idempotency path)

```
Client          Gateway          Gateway DB       Account Service
  │                │                  │            (NOT called)
  │  POST /events  │                  │
  │  (same eventId)│                  │
  │  ─────────────►│                  │
  │                │                  │
  │                │  [1] Validate    │
  │                │  (passes)        │
  │                │                  │
  │                │  [2] findById    │
  │                │  (eventId)       │
  │                │  ───────────────►│
  │                │  EventEntity     │
  │                │  (found!)        │
  │                │  ◄───────────────│
  │                │                  │
  │                │  [3] STOP — do not call Account Service
  │                │      return existing entity
  │                │                  │
  │  200 OK        │                  │
  │  (original     │                  │
  │   event body)  │                  │
  │  ◄─────────────│                  │
```

**Status code semantics:**
- `201 Created` — first successful submission; event was new and applied
- `200 OK` — duplicate; event was already processed; body is identical to original 201 response

---

### 4.3 Sequence: POST /events — Async Fallback (Account Service Down)

```
Client          Gateway          Gateway DB       Account Service
  │                │                  │            (unreachable)
  │  POST /events  │                  │
  │  ─────────────►│                  │
  │                │                  │
  │                │  [1] Validate    │
  │                │  (passes)        │
  │                │                  │
  │                │  [2] findById    │
  │                │  null (new)      │
  │                │  ◄───────────────│
  │                │                  │
  │                │  [3] CircuitBreaker: OPEN or Account Service returns 5xx
  │                │      Fallback: throws ServiceUnavailableException
  │                │                  │
  │                │  [4] EventService CATCHES ServiceUnavailableException
  │                │      (does NOT propagate to GlobalExceptionHandler)
  │                │                  │
  │                │  [5] save event  │
  │                │  status=QUEUED   │
  │                │  ───────────────►│
  │                │  saved           │
  │                │  ◄───────────────│
  │                │                  │
  │  202 Accepted  │                  │
  │  status=QUEUED │                  │
  │  ◄─────────────│                  │
  │                │                  │
  │                │ ...FallbackQueueProcessor runs every 30s...
  │                │                  │
  │                │  findByStatus    │
  │                │  (QUEUED)        │
  │                │  ───────────────►│
  │                │  [queued events] │
  │                │  ◄───────────────│
  │                │                  │  [Account Service recovered]
  │                │  POST /accounts/{accountId}/transactions
  │                │  ──────────────────────────────────────►│
  │                │  201 Created     │                      │
  │                │  ◄──────────────────────────────────────│
  │                │                  │
  │                │  update status   │
  │                │  PROCESSED       │
  │                │  ───────────────►│
```

**Status code semantics for POST /events:**
- `201 Created` — new event, Account Service confirmed → `status: "PROCESSED"`
- `200 OK` — duplicate event, already in DB → original status returned
- `202 Accepted` — new event, Account Service down → `status: "QUEUED"`, will be retried

**Graceful degradation:** GET endpoints read only from Gateway DB and are completely unaffected
by Account Service availability. POST events are now always accepted (202 instead of 503).

---

### 4.4 Sequence: GET /events?account={accountId} — Sorted List

```
Client          Gateway          Gateway DB
  │                │                  │
  │  GET /events   │                  │
  │  ?account=X    │                  │
  │  ─────────────►│                  │
  │                │                  │
  │                │  findByAccountId │
  │                │  ORDER BY        │
  │                │  event_timestamp │
  │                │  ASC             │
  │                │  ───────────────►│
  │                │                  │
  │                │  [evt-001 ts=T1, │
  │                │   evt-003 ts=T2, │   Note: evt-003 arrived before evt-002
  │                │   evt-002 ts=T3] │   but has earlier eventTimestamp.
  │                │  ◄───────────────│   Sorted by eventTimestamp, not receivedAt.
  │                │                  │
  │  200 OK        │                  │
  │  [sorted list] │                  │
  │  ◄─────────────│                  │
```

---

### 4.5 Race Condition: Concurrent Duplicate Submissions

```
Thread A        Thread B        Gateway DB
  │                │                │
  │  POST (evt-1)  │                │
  │  ─────────────►│  POST (evt-1)  │
  │                │  ─────────────►│
  │                │                │
  │  findById → null               │
  │  ─────────────────────────────►│
  │                │                │
  │  AccountSvc OK │  findById → null (not saved yet)
  │                │  ─────────────────────────────►│
  │                │                │
  │  save event    │                │
  │  ─────────────────────────────►│
  │  INSERT OK     │                │    Thread A wins — event saved.
  │                │                │
  │  201 Created   │                │
  │  ◄─────────────│                │
  │                │  AccountSvc OK │
  │                │                │
  │                │  save event    │
  │                │  ─────────────────────────────►│
  │                │  DataIntegrity │    Thread B — unique constraint violation.
  │                │  Violation!    │
  │                │  ◄─────────────────────────────│
  │                │                │
  │                │  catch DataIntegrityViolation  │
  │                │  → findById(eventId) → found   │
  │                │  → return 200 OK               │
  │  200 OK        │                │
  │  ◄─────────────────────────────│
```

This scenario reveals a subtle consistency gap: Thread B already called Account Service before the
constraint violation. Both threads called Account Service with the same eventId. The Account
Service's unique constraint on `eventId` in its `transactions` table prevents double-apply.
Thread B's Account Service call is a no-op (returns the existing balance).

## 5. Data Model

### EventEntity — Gateway Service

```java
// com.eventledger.gateway.model.EventEntity

@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_events_account_id", columnList = "account_id")
})
public class EventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;                    // PK = idempotency key

    @Column(name = "account_id", nullable = false)
    private String accountId;                  // used in GET by account

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;                    // CREDIT | DEBIT

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;                 // NEVER double/float

    @Column(nullable = false)
    private String currency;                   // e.g. "USD"

    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime eventTimestamp;     // client-provided, used for sort

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;         // server-set on ingestion, audit only

    @Column(columnDefinition = "TEXT")
    private String metadata;                   // raw JSON string, nullable
}
```

**Critical design point:** `eventId` is the primary key. JPA's `save()` will attempt an `INSERT`.
A duplicate `eventId` causes a `DataIntegrityViolationException` at the database level, which is
caught and converted to a 200 OK response with the existing event. This handles the race condition
without requiring pessimistic locking.

### EventRepository — Gateway Service

```java
// com.eventledger.gateway.repository.EventRepository

public interface EventRepository extends JpaRepository<EventEntity, String> {

    // Returns events sorted by eventTimestamp ASC — order is enforced here, not in service.
    // Secondary keys (received_at, then the unique event_id) give a DETERMINISTIC, stable order
    // when two events share the same eventTimestamp (e.g. same-millisecond batch replays).
    List<EventEntity> findByAccountIdOrderByEventTimestampAscReceivedAtAscEventIdAsc(String accountId);
}
```

### TransactionEntity — Account Service

```java
// com.eventledger.accountservice.model.TransactionEntity

@Entity
@Table(name = "transactions",
       uniqueConstraints = @UniqueConstraint(columnNames = "event_id"))
public class TransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;                    // unique constraint = safety-net idempotency

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;              // CREDIT | DEBIT

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime eventTimestamp;
}
```

## 6. API Contract

### POST /events — Processing Flow (ordered steps)

```
Step 1: Deserialize JSON → EventRequest DTO
        Bean Validation (@NotBlank, @NotNull, @Positive, @Valid) runs automatically
        → If any field fails: MethodArgumentNotValidException → 400 with ALL errors

Step 2: EventService.processEvent(request)
        → eventRepository.findById(request.eventId())
        → If present: return existing EventEntity (caller returns 200 OK)

Step 3: accountServiceClient.applyTransaction(request)  ← wrapped in @CircuitBreaker
        → HTTP POST /accounts/{accountId}/transactions
        → If circuit OPEN or call fails: fallback throws ServiceUnavailableException → 503

Step 4: eventRepository.save(new EventEntity(...))
        → If DataIntegrityViolationException (race condition): findById → return 200 OK
        → If save succeeds: return saved EventEntity (caller returns 201 Created)
```

### Validation Rules (Bean Validation annotations on EventRequest record)

```
Field            | Annotation(s)                | Error message
─────────────────────────────────────────────────────────────────────────────
eventId          | @NotBlank                    | "eventId is required"
accountId        | @NotBlank                    | "accountId is required"
type             | @NotNull, valid enum value   | "type must be CREDIT or DEBIT"
amount           | @NotNull, @DecimalMin("0.01")| "amount must be greater than 0"
currency         | @NotBlank                    | "currency is required"
eventTimestamp   | @NotNull, valid ISO 8601     | "eventTimestamp must be a valid ISO 8601 datetime"
```

All validation errors are collected and returned in a single 400 response:
```json
{
  "errors": [
    { "field": "amount",         "message": "amount must be greater than 0" },
    { "field": "eventTimestamp", "message": "eventTimestamp must be a valid ISO 8601 datetime" }
  ]
}
```

**Enum / type-mismatch handling (important):** When `type` is a value other than `CREDIT`/`DEBIT`
(e.g. `"TRANSFER"`), Jackson cannot bind it to the `EventType` enum during deserialization. This
throws `HttpMessageNotReadableException` (caused by `InvalidFormatException`) **before** Bean
Validation runs — so it does NOT arrive as a `MethodArgumentNotValidException` and would, by
default, produce only a generic "malformed body" error. To honor the API contract
(`{"errors":[{"field":"type","message":"type must be CREDIT or DEBIT"}]}`), the
`GlobalExceptionHandler` MUST special-case this:

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
ResponseEntity<ErrorResponse> onUnreadable(HttpMessageNotReadableException ex) {
    if (ex.getCause() instanceof InvalidFormatException ife
            && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
        String field = ife.getPath().isEmpty() ? "type"
                : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
        return badRequestFieldError(field, "type must be CREDIT or DEBIT");
    }
    return badRequestGeneric("Malformed request body");   // genuinely malformed JSON
}
```

The same field-error shape is reused so clients get a uniform `{"errors":[...]}` body for all
validation failures, whether they originate from Bean Validation or enum deserialization.

> Note: the equivalent enum-mismatch case for the ISO-8601 `eventTimestamp` is handled the same
> way — an unparseable timestamp surfaces as an `InvalidFormatException` on an `OffsetDateTime`
> target and is mapped to the `eventTimestamp` field error.

### EventRequest DTO (Java 17 record)

```java
public record EventRequest(
    @NotBlank(message = "eventId is required")
    String eventId,

    @NotBlank(message = "accountId is required")
    String accountId,

    @NotNull(message = "type is required")
    EventType type,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    BigDecimal amount,

    @NotBlank(message = "currency is required")
    String currency,

    @NotNull(message = "eventTimestamp is required")
    OffsetDateTime eventTimestamp,

    Map<String, Object> metadata  // optional
) {}
```

### EventResponse DTO (Java 17 record)

```java
public record EventResponse(
    String eventId,
    String accountId,
    EventType type,
    BigDecimal amount,
    String currency,
    OffsetDateTime eventTimestamp,
    OffsetDateTime receivedAt,
    Map<String, Object> metadata
) {}
```

## 7. Key Design Decisions

### Idempotency Design: eventId as Primary Key

**Approach:** `eventId` is the JPA `@Id` on `EventEntity`. This means JPA uses it as the H2
PRIMARY KEY, which implicitly creates a unique constraint at the DB level.

**Why PK instead of unique index on a surrogate key?**
The eventId uniquely identifies the event — it IS the natural key. Using it as PK avoids having
two unique identifiers for the same concept and removes the need for a separate unique index.

**Race condition handling:** If two threads concurrently pass the `findById` check (both see null),
both proceed to call Account Service and then attempt `INSERT`. The second insert throws
`DataIntegrityViolationException`. The service catches this exception, queries for the existing
event by `eventId`, and returns it with 200 OK.

This is a deliberate "optimistic" approach: no locking, rely on DB constraint, handle the exception.
Locking alternatives (pessimistic lock, `SELECT FOR UPDATE`) add overhead for what should be a rare
race. The eventual outcome is correct in both cases.

**Account Service dual idempotency:** The Account Service also stores `eventId` with a unique
constraint. In the race condition described above, the second thread's Account Service call may
succeed or fail (depending on timing). If it succeeds, Account Service's unique constraint prevents
double-apply. If it fails, the Gateway catches the exception and still returns 200 OK.

### Out-of-Order Event Tolerance

**Two timestamps per event:**

```
eventTimestamp (client-provided):   When the event OCCURRED in the real world
                                    ISO 8601 in the request payload
                                    Used for: sorting, display, business logic

receivedAt (server-set):            When the Gateway RECEIVED the event
                                    Set to Instant.now() at ingestion
                                    Used for: audit trail, debugging, operations
```

**Sorting is delegated to the repository layer:**

```java
findByAccountIdOrderByEventTimestampAscReceivedAtAscEventIdAsc(String accountId)
```

This generates `ORDER BY event_timestamp ASC, received_at ASC, event_id ASC` at the SQL level. The
service layer receives an already-sorted list and returns it directly. No in-memory sorting is
performed.

**Deterministic tie-break (why three sort keys):** `event_timestamp` alone is not unique — upstream
systems (mainframe batches, payment networks) routinely emit several events stamped with the *same*
instant. With a single sort key, SQL leaves the order of tied rows undefined, so the same listing
could come back in different orders across calls or across databases (H2 vs. a production RDBMS).
Adding `received_at` (ingestion order — the natural intent for "same instant" events) and finally
the unique `event_id` guarantees a total, stable, repeatable ordering. Note this affects *listing
determinism only* — balance is an order-independent aggregate (below) and is unaffected.

Because `event_timestamp` and `received_at` are `OffsetDateTime`, Hibernate 6 normalizes them to
UTC before persisting, so comparison is by true instant — events bearing different timezone offsets
sort by the moment they actually occurred, not by wall-clock local time.

**Balance correctness:** `balance = Σ(amount WHERE type = CREDIT) − Σ(amount WHERE type = DEBIT)`
This formula is commutative and associative — the order events arrive in has no effect on the final
balance. All events for an account are summed, regardless of when they were received or what their
timestamps are.

### Balance Computation

The Account Service computes balance using JPQL aggregate queries, not in-memory summation:

```sql
SELECT COALESCE(SUM(t.amount), 0)
FROM TransactionEntity t
WHERE t.accountId = :accountId AND t.type = 'CREDIT'

SELECT COALESCE(SUM(t.amount), 0)
FROM TransactionEntity t
WHERE t.accountId = :accountId AND t.type = 'DEBIT'
```

`balance = creditSum.subtract(debitSum)` — pure `BigDecimal` arithmetic.

**Decision: derive balance on read (aggregate query) — this is canonical.** The `accounts` table
stores only `accountId` and `currency`; there is no persisted `balance` column (see
system-overview.md §5). The transactions table is an immutable ledger and the single source of
truth. This is correct by definition, independent of arrival order, and avoids the
read-modify-write race that a denormalized balance field would introduce.

A persisted, incrementally-updated balance column was considered and **rejected**: it is faster for
read-heavy workloads but requires careful transactional locking to avoid drift/races, which is not
justified at assessment scale. If profiling later showed balance reads to be hot, a cached balance
could be added behind the same `getBalance()` contract without changing the API.

## 8. Error Handling Strategy

```
Scenario                          | Handled by              | Response
─────────────────────────────────────────────────────────────────────────────────────────
Validation failure (any field)    | @Valid + ControllerAdvice| 400 all errors collected
Unknown enum value for "type"     | InvalidFormatException   | 400 field error {"field":"type","message":"type must be CREDIT or DEBIT"}
Unparseable eventTimestamp        | InvalidFormatException   | 400 field error {"field":"eventTimestamp",...}
eventId already exists (findById) | EventService             | 200 existing event (not error)
eventId race (DataIntegrity)      | EventService catch block | 200 existing event (not error)
Account Service circuit open      | @CircuitBreaker fallback | 503 DEPENDENCY_UNAVAILABLE
Account Service HTTP error (5xx)  | @CircuitBreaker fallback | 503 DEPENDENCY_UNAVAILABLE
Account Service timeout           | Circuit breaker slow call| 503 (counts toward failure rate)
Event not found (GET)             | EventService             | throws EventNotFoundException → 404
Malformed JSON body               | HttpMessageConverter     | 400 malformed request
Unexpected exception              | GlobalExceptionHandler   | 500 (no stack trace to client)
```

**Exception hierarchy (Gateway):**

```
RuntimeException
├── EventNotFoundException        → 404 "Event not found: {id}"
└── ServiceUnavailableException   → 503 "Account Service unavailable"
    (thrown by circuit breaker fallback method)
```

**No stack traces to clients:** `GlobalExceptionHandler` logs at ERROR with traceId in MDC, but
the response body contains only a human-readable message and optional error code.

## 9. Observability

### Log Points in Event Processing Flow

```
Layer                    | Level | Message (with MDC: traceId, eventId, accountId)
─────────────────────────────────────────────────────────────────────────────────────
EventController.submit   | INFO  | "POST /events received eventId={} accountId={}"
EventService (duplicate) | INFO  | "Duplicate event detected eventId={} — returning existing"
EventService (new)       | INFO  | "Processing new event eventId={} accountId={}"
AccountServiceClient     | DEBUG | "Calling Account Service POST /accounts/{}/transactions"
AccountServiceClient     | INFO  | "Account Service responded status={} accountId={}"
AccountServiceClient(CB) | WARN  | "Circuit breaker open — returning 503 for accountId={}"
EventService (saved)     | INFO  | "Event saved eventId={} accountId={}"
EventController (201)    | INFO  | "Event accepted eventId={} status=201"
EventController (200)    | INFO  | "Duplicate event returned eventId={} status=200"
EventController (503)    | WARN  | "Account Service unavailable eventId={} status=503"
GlobalExceptionHandler   | ERROR | "Unexpected error: {}" (with full exception in log only)
```

### Micrometer Counters

```
events.received.total          tags: type=CREDIT/DEBIT, outcome=NEW/DUPLICATE
events.rejected.total          tags: reason=VALIDATION/CIRCUIT_OPEN
account.service.calls.total    tags: outcome=SUCCESS/FAILURE
```

### Trace Context

- `traceId` injected into MDC by Micrometer Tracing (auto-configured in Spring Boot 3)
- Appears in every JSON log line as `"traceId": "4bf92f3577b34da6"`
- Propagated to Account Service via the W3C `traceparent` header AND an explicit `X-Trace-Id`
  header (see ADR-004 in resiliency-and-observability.md). `X-Trace-Id` is the header the
  trace-propagation test (T-10) asserts on; both carry the same trace id.
- No manual MDC.put() required — Spring Boot's tracing auto-configuration handles it

## 10. Testing Strategy

### Unit Tests — EventService

```
- processEvent(duplicate) → returns existing entity, does not call AccountServiceClient
- processEvent(new) → calls AccountServiceClient once, saves event, returns 201
- processEvent(circuit open) → throws ServiceUnavailableException, does NOT save event
- processEvent(race condition) → catches DataIntegrityViolationException → returns 200
```

### Controller Slice Tests — @WebMvcTest

```
- POST /events missing eventId → 400 with {"errors":[{field:"eventId",message:"..."}]}
- POST /events amount=0 → 400
- POST /events amount=-5 → 400
- POST /events type="TRANSFER" → 400
- POST /events all valid → 201 (service mocked)
- POST /events duplicate → 200 (service mocked)
- GET /events/{id} found → 200
- GET /events/{id} not found → 404
- GET /events?account=X → 200 sorted list
```

### Integration Tests — WireMock + @SpringBootTest

```
T-1: POST same eventId twice → assert second is 200 OK with identical body
T-2: POST same eventId twice → WireMock verifies Account Service called exactly once
T-3: POST 3 events out-of-order → GET returns them sorted by eventTimestamp ASC
T-8: WireMock returns 500 for 10 requests → assert circuit opens → assert 503 returned
T-9: GET /events works after circuit opens (reads Gateway DB only)
T-10: POST /events → WireMock verifies X-Trace-Id / traceparent header received
```

## 11. Open Questions

- Should duplicate 200 responses include a `"duplicate": true` flag in the response body to allow
  clients to distinguish from a normal 200? Not required by spec, but useful for observability.
  Current decision: no extra field — use HTTP status codes as the signal (201 vs 200).
- If Account Service returns 400 (bad request) on a valid event, should Gateway return 400 or 500?
  Current decision: 500 — this indicates a Gateway bug (sending malformed data). Account Service
  400s should not be surfaced as client errors on the Gateway.
- Should `receivedAt` be included in the EventResponse DTO? Useful for debugging out-of-order
  scenarios. Current decision: include it in the response to aid operational troubleshooting.
- Metadata deserialization: store as `Map<String, Object>` in the DTO but as a JSON string in the
  entity (H2 TEXT column). Jackson handles serialization in/out. This is the simplest approach
  without requiring a separate metadata table or H2 JSON column type.
