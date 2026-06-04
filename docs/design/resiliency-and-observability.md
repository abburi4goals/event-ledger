# Design Document: Resiliency and Observability

## 1. Overview

This document covers the resiliency and observability design for the Event Ledger system. The
Gateway uses a Resilience4j circuit breaker to protect against Account Service unavailability,
providing fast-fail semantics and automatic recovery probing. Both services implement distributed
tracing via Micrometer Tracing with an OpenTelemetry bridge, structured JSON logging via Logback,
and custom health endpoints that verify database connectivity.

## 2. Goals

- Prevent Gateway threads from hanging or exhausting on Account Service failures
- Queue events locally when Account Service is down and forward them automatically when it recovers
- Allow GET endpoints on the Gateway to work at all times, regardless of Account Service state
- Give a complete trace of every client request across both services via a shared trace ID
- Produce machine-parseable (JSON) log output with trace context on every line
- Give operators a simple health signal (UP/DOWN with DB status) without requiring Actuator paths

## 3. Non-Goals

- Bulkhead / thread pool isolation (circuit breaker is the chosen primary pattern)
- Retry with exponential backoff (bonus item — can be layered below the circuit breaker)
- Distributed tracing UI (Jaeger / Zipkin collector — bonus Docker Compose addition)
- Prometheus remote scraping (bonus — `/actuator/prometheus` endpoint)
- Alerting rules or SLO definitions
- Distributed rate limiting (in-memory per-IP rate limiting is implemented; see §4.5)

## 4. Architecture

### 4.1 Circuit Breaker State Machine

```
                         ┌─────────────────────────────┐
                         │  Failure rate ≥ 50%          │
                         │  in 10-call sliding window   │
                         │  (5+ of last 10 calls fail)  │
                         └──────────────┬───────────────┘
                                        │
                                        ▼
              ┌──────────────────────────────────────────────────────┐
              │                                                      │
   ──────────►│   CLOSED   │──────── failure threshold met ─────────►│   OPEN   │
   (normal    │  (normal   │                                         │ (fast-fail│
    traffic)  │  operation)│◄────────────────────────────────        │  return  │
              │            │    all probe calls succeed     │        │   503)   │
              └────────────┘                                │        └────┬─────┘
                                                            │             │
                                                            │    waitDuration
                                                            │    = 30s expires
                                                            │             │
                                                            │             ▼
                                                            │  ┌──────────────────┐
                                                            │  │   HALF_OPEN      │
                                                            └──│  (3 probe calls  │
                                                               │   allowed)       │
                                                               └──────────────────┘
                                                                        │
                                                                        │ any probe call fails
                                                                        ▼
                                                                      OPEN
                                                               (wait another 30s)
```

**State transition summary:**

```
CLOSED    → OPEN:      Failure rate ≥ 50% over 10-call sliding window
OPEN      → HALF_OPEN: waitDurationInOpenState (30s) expires
HALF_OPEN → CLOSED:    All permittedNumberOfCallsInHalfOpenState (3) probe calls succeed
HALF_OPEN → OPEN:      Any probe call in HALF_OPEN state fails
```

### 4.2 Circuit Breaker Configuration

```yaml
# application.yml — event-gateway

resilience4j:
  circuitbreaker:
    instances:
      accountService:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10              # Evaluate over last 10 calls
        minimumNumberOfCalls: 10           # Compute failure rate only after 10 calls
                                           # (CRITICAL: Resilience4j defaults this to 100 —
                                           #  without it the circuit never opens in a 10-call window)
        failureRateThreshold: 50           # Open if ≥ 50% fail (5+ of 10)
        waitDurationInOpenState: 30s       # Stay OPEN for 30s before probing
        permittedNumberOfCallsInHalfOpenState: 3  # Allow 3 probe calls
        slowCallDurationThreshold: 2s      # Calls > 2s count as slow
        slowCallRateThreshold: 80          # Open if ≥ 80% of calls are slow
        registerHealthIndicator: true      # Expose state via /actuator/health
```

| Property                                 | Value | Rationale                                                     |
|------------------------------------------|-------|---------------------------------------------------------------|
| slidingWindowSize                        | 10    | Small window — assessment; 50+ calls in production           |
| minimumNumberOfCalls                     | 10    | **Must be set.** Resilience4j defaults to 100 — leaving it unset means the circuit never evaluates (or opens) until 100 calls are recorded, even with a 10-call window. Set to 10 so the circuit can open after one full window. |
| failureRateThreshold                     | 50    | 50% failure rate — balanced sensitivity                       |
| waitDurationInOpenState                  | 30s   | Long enough to let Account Service recover or restart        |
| permittedNumberOfCallsInHalfOpenState    | 3     | 3 probes to confirm recovery before fully reopening          |
| slowCallDurationThreshold                | 2s    | Account Service should respond in <2s; slow calls = failures |
| slowCallRateThreshold                    | 80    | Open circuit if service is consistently slow, not just erroring|

### 4.3 Circuit Breaker Code Placement

```
Event Gateway call stack:

  EventController.submitEvent(request)
        │
        ▼
  EventService.submitEvent(request)
        │
        ├── eventRepository.findById(eventId)  ← NO circuit breaker
        │
        ├── accountServiceClient.applyTransaction(request)  ◄── @CircuitBreaker HERE
        │         │
        │         ├── [CLOSED] → HTTP POST /accounts/{id}/transactions
        │         ├── [OPEN]   → CallNotPermittedException (no HTTP call)
        │         └── [HALF_OPEN] → HTTP POST (probe call)
        │
        │   fallback: applyTransactionFallback(ex)
        │         └── throws ServiceUnavailableException
        │
        ├── [SUCCESS] → eventRepository.save(event, status=PROCESSED)  → 201 Created
        │
        └── [ServiceUnavailableException caught] → eventRepository.save(event, status=QUEUED)
                                                 → 202 Accepted
                                                 → FallbackQueueProcessor retries async
```

**The circuit breaker wraps only `AccountServiceClient.applyTransaction()`.**
It does NOT wrap:
- `eventRepository.findById()` — reads from local H2, no remote dependency
- `eventRepository.findByAccountIdOrderByEventTimestampAsc()` — local H2
- `eventRepository.save()` — local H2
- `GET /health` — local health check only

### 4.3a Async Fallback Queue

`FallbackQueueProcessor` is a `@Scheduled` Spring component that runs every 30 seconds:

```
FallbackQueueProcessor.processQueuedEvents()   ← @Scheduled(fixedDelay=30s)
        │
        ├── eventRepository.findByStatus(QUEUED)
        │
        └── for each queued event:
              accountServiceClient.applyTransaction(...)
                ├── [SUCCESS] → event.setStatus(PROCESSED) → save
                └── [ServiceUnavailableException] → leave as QUEUED, log WARN, retry next cycle
```

**EventStatus lifecycle:**

```
POST /events (Account Service UP)   →  status = PROCESSED  →  stored, 201 Created
POST /events (Account Service DOWN) →  status = QUEUED     →  stored, 202 Accepted
FallbackQueueProcessor (recovery)   →  status QUEUED → PROCESSED
```

### 4.4 Graceful Degradation Table

```
┌─────────────────────────────────┬─────────────────────────┬──────────────────────────────────────┐
│ Client Request                  │ Account Service State   │ Expected Behavior                    │
├─────────────────────────────────┼─────────────────────────┼──────────────────────────────────────┤
│ POST /events (new event)        │ UP (circuit CLOSED)     │ 201 Created — event stored           │
│                                 │                         │ (status=PROCESSED), balance updated  │
├─────────────────────────────────┼─────────────────────────┼──────────────────────────────────────┤
│ POST /events (new event)        │ DOWN (circuit OPEN)     │ 202 Accepted — event stored          │
│                                 │                         │ (status=QUEUED); FallbackQueue-      │
│                                 │                         │ Processor forwards it on recovery    │
├─────────────────────────────────┼─────────────────────────┼──────────────────────────────────────┤
│ POST /events (duplicate)        │ UP or DOWN              │ 200 OK — return original event       │
│                                 │ (any state)             │ immediately; Account Service NOT     │
│                                 │                         │ called                                │
├─────────────────────────────────┼─────────────────────────┼──────────────────────────────────────┤
│ GET /events/{id}                │ UP or DOWN              │ 200 OK or 404 Not Found              │
│                                 │ (any state)             │ (Gateway DB only — unaffected by     │
│                                 │                         │  circuit state)                       │
├─────────────────────────────────┼─────────────────────────┼──────────────────────────────────────┤
│ GET /events?account=X           │ UP or DOWN              │ 200 OK with sorted event list        │
│                                 │ (any state)             │ (Gateway DB only — unaffected)       │
├─────────────────────────────────┼─────────────────────────┼──────────────────────────────────────┤
│ GET /health (Gateway)           │ UP or DOWN              │ 200 OK with Gateway's own DB status  │
│                                 │ (any state)             │ (local check only)                   │
└─────────────────────────────────┴─────────────────────────┴──────────────────────────────────────┘
```

**Key insight:** No client request is now hard-blocked by circuit state. `POST /events` for a new
event is queued when Account Service is down (202) instead of failing (503). All read paths and
duplicate submissions are unaffected.

**Auth note:** `ApiKeyAuthFilter` runs at `@Order(1)`, before the circuit breaker is ever reached.
A request with a missing or invalid `X-Api-Key` returns `401` immediately — the circuit breaker
call counter is NOT incremented. Only authenticated requests that proceed to `AccountServiceClient`
affect circuit state.

### 4.5 Rate Limiting

Per-client-IP token bucket, implemented as `RateLimitFilter` (`@Order(2)`) using Resilience4j
`RateLimiter`. Runs after `ApiKeyAuthFilter` so only authenticated requests consume rate limit
budget. Skips `/health` (same exemption as the auth filter).

**Configuration (`application.yml`):**

```yaml
gateway:
  rate-limit:
    requests-per-second: ${GATEWAY_RATE_LIMIT_RPS:60}
```

| Property | Default | Rationale |
|---|---|---|
| `requests-per-second` | 60 | 1 request per 16ms — appropriate for a financial event ingestor that batches upstream; tune via `GATEWAY_RATE_LIMIT_RPS` env var |
| Refresh period | 1s | Token bucket refills fully every second |
| Timeout | 0ms | Fail immediately when limit exceeded — no queuing |
| Key | Client IP (X-Forwarded-For first, else remoteAddr) | Per-client isolation; one abusive caller cannot throttle others |

**Response when limit exceeded:**

```
HTTP 429 Too Many Requests
Content-Type: application/json
Retry-After: 1

{"error":"Rate limit exceeded","code":"TOO_MANY_REQUESTS"}
```

**Filter chain order:**
```
Inbound Request
    │
    ▼ @Order(1)
ApiKeyAuthFilter     → 401 if invalid key (rate limit never consumed)
    │
    ▼ @Order(2)
RateLimitFilter      → 429 if per-IP budget exhausted
    │
    ▼
EventController      → business logic
```

**Production upgrade path:** The in-memory `ConcurrentHashMap` grows with distinct IPs and is
not evicted. For a multi-instance deployment, replace with a Caffeine cache (TTL eviction) or
Redis-backed Bucket4j for shared rate limit state across replicas.

## 5. Data Model

### EventStatus Enum (Gateway)

The async fallback queue introduces a `status` column on the `events` table:

| Value | Meaning |
|---|---|
| `PROCESSED` | Account Service confirmed the transaction; balance updated |
| `QUEUED` | Account Service was unavailable; event saved locally, awaiting retry |

`status` is set at INSERT time and updated to `PROCESSED` by `FallbackQueueProcessor` on successful retry. It is also returned in `EventResponse` so clients can poll `GET /events/{id}` to check when a QUEUED event transitions to PROCESSED.

Resilience4j circuit breaker state itself is held in-memory (not persisted). The `CallNotPermittedException` thrown when the circuit is OPEN is a transient Java exception.

Observability data models (log fields) are covered in the Observability section.

## 6. API Contract

### Async Fallback Response (Account Service Down)

When the Account Service is unavailable, the event is accepted and queued:

```json
HTTP 202 Accepted
Content-Type: application/json

{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "eventTimestamp": "2024-01-15T10:00:00Z",
  "receivedAt": "2026-06-03T20:00:00Z",
  "status": "QUEUED"
}
```

The `status: "QUEUED"` field signals to the client that the event has been accepted but not yet
forwarded to the Account Service. The client can poll `GET /events/{id}` and check `status`:
- `QUEUED` → still pending retry by the background processor
- `PROCESSED` → Account Service confirmed; balance has been updated

### Normal Success Response

```json
HTTP 201 Created

{ ..., "status": "PROCESSED" }
```

### Duplicate Response

```json
HTTP 200 OK

{ ..., "status": "PROCESSED" }   ← or "QUEUED" if original was queued
```

### Health Endpoint — Both Services

```
GET /health

200 OK — service and DB are UP:
{
  "status": "UP",
  "service": "event-gateway",    ← or "account-service"
  "db": "UP"
}

200 OK — DB is unreachable (service can respond but DB is down):
{
  "status": "DOWN",
  "service": "event-gateway",
  "db": "DOWN"
}
```

Note: Health endpoint always returns HTTP 200 (not 503) — the status field in the body signals
health state. This simplifies load balancer health check configuration.

## 7. Key Design Decisions

---

### ADR-004: Why Micrometer Tracing + OpenTelemetry Bridge (over manual trace propagation)

#### Context

Both services need to log a shared trace ID on every log line, and the Gateway needs to propagate
that trace ID to the Account Service via HTTP headers. Three approaches were considered:
manual UUID generation and header forwarding, Micrometer Tracing, and full OpenTelemetry SDK.

#### Decision

Use Micrometer Tracing (`micrometer-tracing-bridge-otel`) with Spring Boot 3 auto-configuration.
W3C `traceparent` header is propagated via a `RestTemplate` interceptor
(`TracingClientHttpRequestInterceptor`). Both services log `traceId` via MDC auto-injection.

**Two headers are propagated, by design:**

| Header | Source | Purpose |
|---|---|---|
| `traceparent` | Micrometer/OTel auto-propagation | W3C TraceContext — full trace+span context; interoperable with Jaeger/Zipkin/Datadog |
| `X-Trace-Id` | Custom `ClientHttpRequestInterceptor` that copies `tracer.currentSpan().context().traceId()` | Stable, human-readable trace id. This is the header CLAUDE.md mandates and the header tests assert on (T-10). |

Rationale: Micrometer alone sends **`traceparent`**, NOT `X-Trace-Id`. The project requirement
(CLAUDE.md) and the trace-propagation test (T-10) reference `X-Trace-Id` explicitly, so a thin
custom interceptor adds it alongside `traceparent`. The Account Service derives its MDC `traceId`
from the standard context (populated from `traceparent`); `X-Trace-Id` is the explicit, asserted
contract and a fallback if W3C propagation is ever disabled. Both headers carry the same trace id.

```
Dependency chain (event-gateway/pom.xml):
  spring-boot-starter-actuator          ← enables Micrometer
  micrometer-tracing-bridge-otel        ← OTel bridge
  opentelemetry-exporter-otlp (optional) ← for Jaeger/OTLP export (bonus)

Spring Boot 3 auto-configuration:
  - Creates ObservationRegistry
  - Creates Tracer (backed by OTel SDK)
  - Injects traceId + spanId into SLF4J MDC automatically
  - Configures RestTemplate/WebClient with tracing propagation
```

**W3C traceparent header format:**
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
              ^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^
              v  traceId (32 hex chars)           spanId (16 hex)  flags
```

Both services log the same `traceId` for a single client request, enabling cross-service log
correlation without a tracing backend.

#### Consequences

##### Positive
- Zero manual MDC.put() calls: Spring Boot auto-injects traceId into every log line
- W3C traceparent is the industry standard — compatible with Jaeger, Zipkin, Datadog, etc.
- Same traceId appears in both Gateway and Account Service logs for a single request
- RestTemplate auto-propagates headers when configured with `TracingClientHttpRequestInterceptor`
- Bonus: can add OTLP exporter to Docker Compose for visual trace exploration in Jaeger
- Micrometer abstraction means the OTel backend can be swapped without changing application code

##### Negative
- Adds two dependencies to each service (`micrometer-tracing-bridge-otel` + OTel SDK jars)
- Auto-configuration can be opaque — debugging tracing issues requires understanding Spring Boot
  auto-config order
- W3C traceparent requires both services to support the same propagation format — if a third
  service uses B3 headers, additional bridge configuration is needed

##### Alternatives Considered
- **Manual UUID + X-Trace-Id header**: Generate UUID in Gateway, set MDC manually, forward as
  `X-Trace-Id` header to Account Service, extract and set MDC in Account Service filter.
  Simpler code, zero dependencies. Rejected because it requires manual MDC management in every
  log statement and does not produce standard OTel trace IDs. Makes future Jaeger integration
  harder.
- **Full OpenTelemetry SDK (no Micrometer bridge)**: More control, standard OTel API.
  Heavier setup for a Spring Boot app — Micrometer is already included via Actuator. The bridge
  provides OTel semantics with Spring Boot's ergonomics. Rejected as unnecessarily low-level for
  this assessment.
- **Spring Cloud Sleuth**: Deprecated in Spring Boot 3 (replaced by Micrometer Tracing). Rejected.

#### Status
Accepted

#### Date
2026-06-02

## 8. Error Handling Strategy

### Circuit Breaker + Async Fallback Exception Flow

```
Normal call path (Account Service UP):
EventService.submitEvent()
  → accountServiceClient.applyTransaction()
      → HTTP POST /accounts/{id}/transactions → 201
  → event saved with status=PROCESSED
  → return 201 Created

Fallback path (Account Service DOWN or circuit OPEN):
EventService.submitEvent()
  → accountServiceClient.applyTransaction()
      → [circuit OPEN]  Resilience4j throws CallNotPermittedException → fallback
      → [HTTP 500/timeout] RestTemplate throws → Resilience4j records failure → fallback
      → fallback: applyTransactionFallback() throws ServiceUnavailableException
  → EventService CATCHES ServiceUnavailableException
  → event saved with status=QUEUED
  → return 202 Accepted (body contains status="QUEUED")

Background retry (FallbackQueueProcessor, every 30s):
  → eventRepository.findByStatus(QUEUED)
  → for each: accountServiceClient.applyTransaction()
      → [SUCCESS] → event.status = PROCESSED → save → log INFO
      → [FAIL]    → leave QUEUED → log WARN → retry next cycle
```

### Health Check DB Verification

```java
// HealthController (both services)

@GetMapping("/health")
public HealthResponse health() {
    boolean dbUp = checkDatabaseHealth();
    String status = dbUp ? "UP" : "DOWN";
    return new HealthResponse(status, serviceName, dbUp ? "UP" : "DOWN");
}

private boolean checkDatabaseHealth() {
    try {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return true;
    } catch (Exception e) {
        log.warn("Health check DB query failed: {}", e.getMessage());
        return false;
    }
}
```

H2 in-memory will reliably return `"db": "UP"` during normal operation. The check is defensive
— it confirms the JPA data source connection pool can reach the in-memory DB.

## 9. Observability

### 9.1 Distributed Tracing — Trace Propagation Flow

```
Client Request arrives at Event Gateway
         │
         │  Spring Boot auto-starts a new trace
         │  Micrometer Tracing generates traceId = "4bf92f3577b34da6"
         │  MDC automatically populated: {"traceId": "4bf92f3577b34da6"}
         │
         ▼
  EventController.submitEvent()
  → log.info("POST /events received eventId=...")
    JSON output: {"traceId":"4bf92f3577b34da6", "message":"POST /events received..."}
         │
         ▼
  EventService.processEvent()
  → log.info("Processing new event ...")
    JSON output: {"traceId":"4bf92f3577b34da6", "message":"Processing new event..."}
         │
         ▼
  AccountServiceClient.applyTransaction()
  → RestTemplate with TracingClientHttpRequestInterceptor (+ X-Trace-Id interceptor)
  → HTTP POST /accounts/acct-123/transactions
    Header: traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
    Header: X-Trace-Id:  4bf92f3577b34da6                       ← explicit, asserted by T-10
         │
         ▼ (Account Service receives request)
  Spring Boot extracts traceparent header
  MDC populated: {"traceId": "4bf92f3577b34da6"}  ← SAME traceId
         │
         ▼
  AccountController.applyTransaction()
  → log.info("Applying transaction ...")
    JSON output: {"traceId":"4bf92f3577b34da6", "message":"Applying transaction..."}
         │
         ▼
  AccountService.applyTransaction()
  → log.info("Transaction applied, new balance=...")
    JSON output: {"traceId":"4bf92f3577b34da6", "message":"Transaction applied..."}
```

**Result:** Log search for `traceId = "4bf92f3577b34da6"` in both services returns all log lines
for that single client request, in chronological order.

### 9.2 Structured Logging (JSON) — Configuration

```xml
<!-- logback-spring.xml (both services) -->
<configuration>
  <springProperty scope="context" name="SERVICE_NAME" source="spring.application.name"/>

  <appender name="JSON_STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <customFields>{"service":"${SERVICE_NAME}"}</customFields>
      <fieldNames>
        <timestamp>timestamp</timestamp>
        <level>level</level>
        <message>message</message>
        <logger>logger</logger>
        <thread>thread</thread>
      </fieldNames>
      <includeMdcKeyNames>traceId</includeMdcKeyNames>
      <includeMdcKeyNames>spanId</includeMdcKeyNames>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="JSON_STDOUT"/>
  </root>
</configuration>
```

**Required log line fields:**

```
Field         │ Source                   │ Example
──────────────┼──────────────────────────┼───────────────────────────────────────
timestamp     │ Logback (auto)           │ "2026-06-02T10:30:00.123Z"
level         │ Logback (auto)           │ "INFO"
service       │ customFields (app name)  │ "event-gateway"
traceId       │ MDC (Micrometer Tracing) │ "4bf92f3577b34da6"
spanId        │ MDC (Micrometer Tracing) │ "00f067aa0ba902b7"
message       │ Logger call              │ "Event received"
logger        │ Logback (auto)           │ "c.e.gateway.controller.EventController"
thread        │ Logback (auto)           │ "http-nio-8080-exec-1"
```

**Optional contextual fields (added via MDC per request):**

```
eventId       │ MDC.put("eventId", ...)  │ "evt-001"
accountId     │ MDC.put("accountId", ...)│ "acct-123"
```

**Full example JSON log line:**

```json
{
  "timestamp": "2026-06-02T10:30:00.123Z",
  "level": "INFO",
  "service": "event-gateway",
  "traceId": "4bf92f3577b34da6",
  "spanId": "00f067aa0ba902b7",
  "message": "Event received",
  "eventId": "evt-001",
  "accountId": "acct-123",
  "logger": "com.eventledger.gateway.controller.EventController",
  "thread": "http-nio-8080-exec-1"
}
```

### 9.3 Log Level Conventions

```
DEBUG  │ Detailed call info: HTTP requests/responses, SQL queries (dev profile only)
INFO   │ Normal business events: event received, event saved, transaction applied
WARN   │ Expected operational issues: duplicate event, circuit breaker open, DB slow
ERROR  │ Unexpected failures: uncaught exceptions, DataIntegrityViolation in wrong context
```

### 9.4 Metrics

**Auto-instrumented by Micrometer + Spring Boot:**

```
http.server.requests          → counter + timer, tagged by uri/method/status
  Example: http.server.requests{method=POST,uri=/events,status=201}.count

jvm.memory.used               → gauge, tagged by area (heap/nonheap)
jvm.gc.pause                  → timer
logback.events                → counter, tagged by level (error/warn/info)
```

**Resilience4j auto-instruments circuit breaker:**

```
resilience4j.circuitbreaker.state                → gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j.circuitbreaker.calls.total          → counter, tagged by kind/outcome
resilience4j.circuitbreaker.failure.rate         → gauge (0.0–100.0)
resilience4j.circuitbreaker.not.permitted.calls  → counter (OPEN state fast-fails)
```

**Custom application counters (manually defined):**

```java
// EventService constructor
Counter eventsReceivedNew = Counter.builder("events.received.total")
    .tag("outcome", "NEW")
    .description("New events processed successfully")
    .register(meterRegistry);

Counter eventsReceivedDuplicate = Counter.builder("events.received.total")
    .tag("outcome", "DUPLICATE")
    .description("Duplicate events returned")
    .register(meterRegistry);

Counter eventsRejected = Counter.builder("events.rejected.total")
    .tag("reason", "CIRCUIT_OPEN")
    .description("Events rejected due to circuit breaker")
    .register(meterRegistry);
```

Metrics are accessible via `/actuator/metrics/events.received.total` (Spring Actuator).
Bonus: expose `/actuator/prometheus` for Prometheus scraping.

### 9.5 Health Endpoints

Both services expose a dedicated `GET /health` endpoint (not `/actuator/health`).

```
GET /health — event-gateway

200 OK
{
  "status": "UP",
  "service": "event-gateway",
  "db": "UP",
  "circuitBreakerState": "CLOSED"   ← optional, useful for operational visibility
}
```

The `circuitBreakerState` field is an optional addition — useful for quickly checking whether the
circuit is OPEN without querying Actuator metrics directly.

## 10. Testing Strategy

### Circuit Breaker Tests (WireMock)

```
T-8: Async Fallback — Events queued when Account Service is down
  Setup:
    - WireMock stubs POST /accounts/*/transactions to return HTTP 500
  Assert:
    - POST /events returns 202 Accepted (not 503)
    - Response body contains "status": "QUEUED"
    - At least some requests are queued (circuitOpenCount or queuedCount > 0)
  Note: With async fallback, events are never lost when Account Service is down —
  they are stored as QUEUED and retried by FallbackQueueProcessor.

T-9: Circuit Breaker — GET endpoints unaffected
  Setup: same WireMock stub (Account Service down), circuit OPEN
  Actions:
    - GET /events/{id} (seed data pre-loaded)
    - GET /events?account=X
  Assert:
    - Both return 200 OK (read from Gateway DB, circuit irrelevant)

T-10: Trace Propagation
  Setup: WireMock stubs POST /accounts/*/transactions to return 201
  Action: POST /events with valid payload
  Assert:
    - WireMock received request with header "X-Trace-Id" present and non-blank
      (primary contract — see ADR-004), matching the traceId in the Gateway's logs
    - AND header "traceparent" matching W3C format (regex: "00-[0-9a-f]{32}-[0-9a-f]{16}-01")
```

### Health Endpoint Tests

```
T-12: Health — Both services UP
  Setup: @SpringBootTest (H2 starts in-memory)
  Action: GET /health on both services
  Assert:
    - HTTP 200
    - Body: {"status":"UP","service":"...","db":"UP"}
```

### Observability Tests (Log Verification)

Not formally tested in the test suite — verified manually during development:
- Start both services, POST /events, check logs contain identical traceId in both outputs
- Search logs by traceId to confirm cross-service correlation

## 11. Open Questions

- Should the `GET /health` endpoint return HTTP 503 when status is DOWN, instead of 200?
  Current decision: always return 200 (the status field in the body carries the health signal).
  Some load balancers (ELB health checks) expect non-2xx to remove an instance from rotation.
  For assessment purposes, 200 always is simpler. Production: consider returning 503 when DOWN.
- Should circuit breaker state be included in the `/health` response?
  Current decision: optional addition — include `"circuitBreakerState"` in the response if it
  adds clarity during development/demo. Not part of the formal spec.
- Retry policy layered below circuit breaker (bonus item): if added, set maxAttempts=2,
  waitDuration=100ms with jitter. The circuit breaker treats each retry attempt as a separate call
  against the sliding window — configure retry to only retry on network errors (not 5xx), to avoid
  rapidly consuming the failure budget.
- OpenTelemetry Collector + Jaeger in Docker Compose (bonus): add `otel-collector` and `jaeger`
  services to `docker-compose.yml`. Configure Gateway and Account Service with
  `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`. No code changes needed — only
  environment variable configuration.
