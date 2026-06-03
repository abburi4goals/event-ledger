# Event Ledger — CLAUDE.md

## Project Context

Take-home assessment for a financial institution AI/engineering role. Build an **Event Ledger** system: two microservices that receive, deduplicate, and process financial transaction events with full observability and resiliency.

Time box: ~3–4 hours of focused work. Quality over completeness — make every piece solid.

---

## Architecture

```
Client → Event Gateway API (port 8080)
              │ REST (sync, with circuit breaker)
              ▼
         Account Service (port 8081, internal only)
```

Both services are independently runnable Spring Boot apps with their own embedded H2 databases. They share **no state** and **no database**.

---

## Technology Stack

| Concern | Choice |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Database | H2 (in-memory, per service) |
| ORM | Spring Data JPA |
| Resiliency | Resilience4j (circuit breaker) |
| Tracing | Micrometer Tracing + OpenTelemetry bridge |
| Logging | Logback with JSON structured output (Logstash encoder) |
| Tests | JUnit 5 + Mockito + Spring Boot Test + WireMock |
| Build | Maven |
| Containers | Docker Compose |

---

## Project Structure

```
event-ledger/
├── event-gateway/          # Public-facing REST API
│   ├── src/main/java/
│   ├── src/test/java/
│   ├── pom.xml
│   └── Dockerfile
├── account-service/        # Internal account state service
│   ├── src/main/java/
│   ├── src/test/java/
│   ├── pom.xml
│   └── Dockerfile
├── docker-compose.yml
├── pom.xml                 # Parent POM
├── README.md
└── Requirements/
```

### Package structure (per service)
```
com.eventledger.gateway/
  ├── controller/
  ├── service/
  ├── repository/
  ├── model/          # JPA entities
  ├── dto/            # Request/response POJOs
  ├── client/         # AccountServiceClient (Resilience4j)
  ├── config/         # CircuitBreaker, tracing, metrics config
  └── exception/      # GlobalExceptionHandler
```

---

## Key Requirements (keep these in mind at all times)

### Idempotency
- `eventId` is the idempotency key — stored in Gateway's DB
- Duplicate POST: return `200 OK` with the original event (not `201`)
- Never forward a duplicate to Account Service

### Out-of-order Tolerance
- Store `eventTimestamp` (from payload) separately from `receivedAt`
- GET /events?account=X — sort by `eventTimestamp` ASC
- Balance = sum of all CREDITs − sum of all DEBITs (not dependent on order)

### Validation (return 400 with message)
- Required: `eventId`, `accountId`, `type`, `amount`, `currency`, `eventTimestamp`
- `type` must be `CREDIT` or `DEBIT`
- `amount` must be > 0
- `eventTimestamp` must be valid ISO 8601

### Circuit Breaker (Resilience4j)
- Wraps `AccountServiceClient.applyTransaction()`
- Fallback: return `503 Service Unavailable` with clear message
- GET endpoints on Gateway must still work when circuit is open
- Configure: sliding window 10 calls, failure threshold 50%, wait 30s

### Distributed Tracing
- Gateway generates `traceId` per request (UUID or Micrometer)
- Propagate via `X-Trace-Id` header to Account Service
- Both services log `traceId` in every log line (MDC)
- Use `W3C TraceContext` propagation format with Micrometer

### Structured Logging (JSON)
Every log line must include: `timestamp`, `level`, `service`, `traceId`, `message`

### Health Endpoints
`GET /health` on both services — return `{ "status": "UP", "db": "UP/DOWN" }`

---

## API Contracts

### Event Gateway (port 8080)

**POST /events** — 201 Created (new), 200 OK (duplicate), 400 (invalid), 503 (account service down)

**GET /events/{id}** — 200 OK, 404 Not Found

**GET /events?account={accountId}** — 200 OK, sorted by eventTimestamp ASC

**GET /health** — 200 OK

### Account Service (port 8081)

**POST /accounts/{accountId}/transactions** — 201 Created, 400 (invalid)

**GET /accounts/{accountId}/balance** — 200 `{ "accountId": "...", "balance": 0.00, "currency": "USD" }`

**GET /accounts/{accountId}** — 200 with account details + recent transactions

**GET /health** — 200 OK

---

## Testing Requirements

Must cover (use JUnit 5 + Spring Boot Test):

1. **Idempotency** — POST same eventId twice, assert second returns 200 with same body, balance unchanged
2. **Out-of-order** — Submit events with non-sequential timestamps, assert listing is sorted, balance is correct
3. **Validation** — Missing fields, zero/negative amount, unknown type → 400
4. **Circuit breaker** — Use WireMock to simulate Account Service failure; assert Gateway returns 503, circuit opens
5. **Trace propagation** — Assert `X-Trace-Id` header is sent from Gateway to Account Service
6. **Integration** — Full flow: POST event → Account Service applies transaction → GET balance reflects it

Run all tests: `mvn test`

---

## Development Commands

```bash
# Build everything
mvn clean package -DskipTests

# Run event-gateway (port 8080)
cd event-gateway && mvn spring-boot:run

# Run account-service (port 8081)
cd account-service && mvn spring-boot:run

# Run all tests
mvn test

# Start with Docker Compose
docker-compose up --build

# Tear down
docker-compose down
```

---

## Coding Standards

- Java 17 — use records for DTOs, `Optional` properly, no raw types
- No `@SuppressWarnings` unless unavoidable
- All JPA entities use `@Column(nullable = false)` where appropriate
- `BigDecimal` for all monetary amounts — never `double` or `float`
- Exception handling via `@ControllerAdvice` / `@ExceptionHandler`
- Validate input at the controller layer with `@Valid` + Bean Validation
- No business logic in controllers — delegate to service layer
- Integration tests use `@SpringBootTest(webEnvironment = RANDOM_PORT)`

---

## Commit Style

Use conventional commits:
- `feat:` new feature
- `fix:` bug fix
- `test:` adding tests
- `chore:` build/config
- `docs:` README/docs

Keep a meaningful commit history — this is assessed. Commit per logical unit of work.

---

## Bonus (time permitting)

In priority order:
1. Retry with exponential backoff + jitter (on Gateway → Account Service calls)
2. Prometheus `/actuator/prometheus` endpoint
3. Async fallback queue (store-and-forward when Account Service is down)
4. OpenTelemetry Collector + Jaeger for trace visualization in Docker Compose
