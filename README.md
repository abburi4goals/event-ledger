# Event Ledger

A financial transaction event ledger built with two Spring Boot microservices. Events are received, deduplicated, and processed with full observability and resiliency.

---

## Architecture

```
Client → Event Gateway (port 8080)
              │ REST + Circuit Breaker
              ▼
         Account Service (port 8081, internal only)
```

### Event Gateway
The public-facing API. Accepts transaction events via `POST /events`, enforces idempotency using `eventId` as a deduplication key, and forwards new events to the Account Service. Stores all events in its own H2 database. Exposes `GET /events` and `GET /events/{id}` for querying.

### Account Service
An internal service that owns account state. Applies CREDIT and DEBIT transactions to account balances, computing balance as the sum of all CREDITs minus all DEBITs — order-independent and always consistent. Never called directly by external clients in production.

### Key Design Decisions
- **Separate databases** — each service has its own embedded H2 instance; no shared state
- **Idempotency** — `eventId` is stored as a unique key in the Gateway; duplicate POSTs return `200 OK` with the original response without touching the Account Service
- **Out-of-order tolerance** — `eventTimestamp` (from the payload) is stored separately from `receivedAt`; balance is derived from a JPQL `SUM` aggregate, not a running total
- **Distributed tracing** — W3C `traceparent` header propagated from Gateway to Account Service; `traceId` appears in every log line via MDC

---

## Documentation

| Document | Path | Description |
|---|---|---|
| System Overview | [docs/design/system-overview.md](docs/design/system-overview.md) | Goals, non-goals, service boundaries, API contracts, and test matrix |
| Event Processing | [docs/design/event-processing.md](docs/design/event-processing.md) | End-to-end event flow, idempotency, out-of-order handling, async fallback sequence diagrams |
| Resiliency & Observability | [docs/design/resiliency-and-observability.md](docs/design/resiliency-and-observability.md) | Circuit breaker config, async fallback queue, distributed tracing, structured logging |
| Requirements Breakdown | [docs/requirements-breakdown.md](docs/requirements-breakdown.md) | Functional and non-functional requirements decomposed into tasks |
| Implementation Plan | [docs/implementation-plan.md](docs/implementation-plan.md) | Phased build plan with file-level task breakdown |
| Client Testing Guide | [docs/Client-tests.md](docs/Client-tests.md) | Full curl examples for every endpoint, auth, rate limiting, and tracing |
| Progress Log | [docs/progress.md](docs/progress.md) | Session-by-session record of decisions made and changes applied |

---

## Prerequisites

- Java 17+
- Maven 3.9+
- Docker Desktop (for Docker Compose mode)

Verify:
```bash
java -version
mvn -version
docker --version
```

---

## Setup

Clone and install dependencies:

```bash
git clone <repo-url>
cd event-ledger
mvn clean package -DskipTests
```

---

## Starting the Services

### Option 1 — Docker Compose (recommended)

Builds and starts both services, the OpenTelemetry Collector, and Jaeger in one command:

```bash
docker-compose up --build
```

| Service | URL |
|---|---|
| Event Gateway | http://localhost:8080 |
| Account Service | http://localhost:8081 |
| Jaeger UI (traces) | http://localhost:16686 |

Tear down:
```bash
docker-compose down
```

### Option 2 — Run locally (two terminals)

**Terminal 1 — Account Service:**
```bash
cd account-service && mvn spring-boot:run
```

**Terminal 2 — Event Gateway:**
```bash
cd event-gateway && mvn spring-boot:run
```

The Gateway defaults to `ACCOUNT_SERVICE_URL=http://localhost:8081`.

---

## Running the Tests

Run the full test suite (unit + integration):

```bash
mvn test
```

Run tests for a single service:

```bash
mvn test -pl event-gateway
mvn test -pl account-service
```

### Test Coverage

| # | Scenario | What is verified |
|---|---|---|
| T-1 | Idempotency — first submission | Returns `201`, event stored, balance updated |
| T-2 | Idempotency — duplicate submission | Returns `200` with original body, balance unchanged |
| T-3 | Out-of-order events | Listing sorted by `eventTimestamp` ASC regardless of arrival order |
| T-4 | Balance correctness | CREDITs − DEBITs computed correctly across out-of-order submissions |
| T-5 | Validation — missing fields | Returns `400` |
| T-6 | Validation — zero/negative amount | Returns `400` |
| T-7 | Validation — unknown event type | Returns `400` |
| T-8 | Async fallback — Account Service down | Gateway returns `202 QUEUED`, event stored for retry |
| T-9 | Circuit breaker — GET endpoints unaffected | `GET /events` still works when circuit is open |
| T-10 | Trace propagation | `X-Trace-Id` / `traceparent` header forwarded to Account Service |
| T-11 | Health endpoints | Both services return `{ "status": "UP", "db": "UP" }` |
| T-12 | Full integration flow | POST event → balance reflected in Account Service |

---

## API Reference

### Event Gateway — port 8080

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/events` | Submit a transaction event | `201` new, `200` duplicate |
| GET | `/events/{id}` | Get event by ID | `200`, `404` |
| GET | `/events?account={id}` | List events for account, sorted by `eventTimestamp` ASC | `200` |
| GET | `/health` | Health check | `200` |

**POST /events request body:**
```json
{
  "eventId": "evt-001",
  "accountId": "acc-123",
  "type": "CREDIT",
  "amount": 500.00,
  "currency": "USD",
  "eventTimestamp": "2024-01-15T10:00:00Z"
}
```

### Account Service — port 8081 (internal)

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/accounts/{accountId}/transactions` | Apply transaction | `201` |
| GET | `/accounts/{accountId}/balance` | Get current balance | `200` |
| GET | `/accounts/{accountId}` | Get account details + transactions | `200` |
| GET | `/health` | Health check | `200` |

See [docs/Client-tests.md](docs/Client-tests.md) for full curl examples.

---

## Resiliency Pattern

### Circuit Breaker (Resilience4j)

The Gateway wraps every call to the Account Service in a Resilience4j circuit breaker:

```
CLOSED (normal) → failures exceed threshold → OPEN (fast-fail)
      ↑                                              │
      └──────── half-open probe succeeds ────────────┘
```

**Configuration:**
- Sliding window: 10 calls
- Failure threshold: 50% — circuit opens after 5 failures in 10 calls
- Wait in OPEN state: 30 seconds before allowing a probe request
- Fallback: event is saved as `QUEUED` and `202 Accepted` is returned; a background processor retries delivery when the Account Service recovers

**Why this matters for a financial system:**
Without a circuit breaker, a slow or unresponsive Account Service causes Gateway threads to pile up waiting, eventually exhausting the thread pool and taking down the Gateway too — a cascading failure. The circuit breaker fails fast, preserves Gateway availability, and gives the Account Service time to recover. GET endpoints (`/events`, `/events/{id}`) bypass the Account Service entirely and remain available even when the circuit is open.

### Retry with Exponential Backoff

Transient network errors (connection reset, brief unavailability) are retried up to 3 times with exponential backoff and jitter before the circuit breaker counts them as failures:

- Attempt 1: immediate
- Attempt 2: ~500ms delay
- Attempt 3: ~1000ms delay + random jitter

---

## Observability

### Structured JSON Logging

Every log line is JSON with `timestamp`, `level`, `service`, `traceId`, `spanId`, and `message`. Example:

```json
{
  "@timestamp": "2024-01-15T10:00:00Z",
  "level": "INFO",
  "service": "event-gateway",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "message": "Event submitted eventId=evt-001 accountId=acc-123"
}
```

### Distributed Tracing

Traces flow from Gateway → Account Service via the W3C `traceparent` header. With Docker Compose running, open Jaeger at http://localhost:16686 to visualize full request traces across both services.

### Metrics

Both services expose Prometheus metrics at `/actuator/prometheus`.
