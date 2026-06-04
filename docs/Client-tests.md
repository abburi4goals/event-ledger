# Client Testing Guide — Event Ledger

## Prerequisites

Start all services before running any tests:

```bash
docker-compose up
```

Wait until both services are healthy (gateway waits for account-service automatically).

---

## Authentication

The Event Gateway (port 8080) requires an API key on every request **except** `GET /health`.
Pass it via the `X-Api-Key` header.

**Local / Docker Compose default key:**
```bash
export API_KEY=test-api-key-secret
```

**Production:** set the `GATEWAY_API_KEY` environment variable in your deployment config.
The Gateway reads `${GATEWAY_API_KEY}` at startup; any request with a missing or wrong key
receives `401 Unauthorized`:
```json
{ "error": "Invalid or missing API key", "code": "UNAUTHORIZED" }
```

The Account Service (port 8081) is **internal-only** and has no authentication — it is not
exposed to external callers and should not be called directly in production.

---

## Health Checks

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

Expected response:
```json
{ "status": "UP", "db": "UP" }
```

---

## Event Gateway Tests (port 8080)

### Submit a New Event
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acc-123",
    "type": "CREDIT",
    "amount": 500.00,
    "currency": "USD",
    "eventTimestamp": "2024-01-15T10:00:00Z"
  }'
```
Expected: `201 Created`

---

### Idempotency — Submit the Same Event Again
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acc-123",
    "type": "CREDIT",
    "amount": 500.00,
    "currency": "USD",
    "eventTimestamp": "2024-01-15T10:00:00Z"
  }'
```
Expected: `200 OK` with the same body (not 201 — duplicate is detected and not reprocessed)

---

### Submit a DEBIT Event
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{
    "eventId": "evt-002",
    "accountId": "acc-123",
    "type": "DEBIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2024-01-15T11:00:00Z"
  }'
```
Expected: `201 Created`

---

### Out-of-Order Events — Earlier Timestamp
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{
    "eventId": "evt-003",
    "accountId": "acc-123",
    "type": "CREDIT",
    "amount": 200.00,
    "currency": "USD",
    "eventTimestamp": "2024-01-14T08:00:00Z"
  }'
```
Expected: `201 Created` (older timestamp accepted — out-of-order tolerance)

---

### Get Event by ID
```bash
curl -H "X-Api-Key: $API_KEY" http://localhost:8080/events/evt-001
```
Expected: `200 OK` with event details, `404 Not Found` for unknown IDs

---

### Get All Events for an Account (sorted by eventTimestamp ASC)
```bash
curl -H "X-Api-Key: $API_KEY" "http://localhost:8080/events?account=acc-123"
```
Expected: `200 OK` — events sorted by `eventTimestamp` ascending (evt-003 → evt-001 → evt-002)

---

### Validation Error — Missing Required Fields
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{"eventId": "evt-004", "accountId": "acc-123"}'
```
Expected: `400 Bad Request`

---

### Validation Error — Negative Amount
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{
    "eventId": "evt-005",
    "accountId": "acc-123",
    "type": "CREDIT",
    "amount": -50.00,
    "currency": "USD",
    "eventTimestamp": "2024-01-15T12:00:00Z"
  }'
```
Expected: `400 Bad Request`

---

### Validation Error — Invalid Event Type
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d '{
    "eventId": "evt-006",
    "accountId": "acc-123",
    "type": "TRANSFER",
    "amount": 50.00,
    "currency": "USD",
    "eventTimestamp": "2024-01-15T12:00:00Z"
  }'
```
Expected: `400 Bad Request`

---

### Authentication Error — Missing API Key
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId": "evt-007", "accountId": "acc-123", "type": "CREDIT", "amount": 10.00, "currency": "USD", "eventTimestamp": "2024-01-15T13:00:00Z"}'
```
Expected: `401 Unauthorized`
```json
{ "error": "Invalid or missing API key", "code": "UNAUTHORIZED" }
```

---

### Rate Limiting — Burst Test

The Gateway allows 60 requests per second per client IP by default (`GATEWAY_RATE_LIMIT_RPS`).
When exceeded, it returns `429 Too Many Requests` with a `Retry-After: 1` header.

To trigger the limit locally, fire a burst of requests using a loop:
```bash
for i in $(seq 1 70); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-Api-Key: $API_KEY" \
    http://localhost:8080/events/nonexistent
done
```
Expected: first ~60 responses are `404` (or `200`), remaining responses are `429`.

Check the `Retry-After` header on a 429:
```bash
curl -v -H "X-Api-Key: $API_KEY" http://localhost:8080/events/nonexistent 2>&1 | grep -E "< HTTP|Retry-After"
```
Expected: `Retry-After: 1`

`GET /health` is **never** rate-limited — it bypasses the filter entirely:
```bash
for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/health
done
```
Expected: all `200`.

---

## Account Service Tests (port 8081)

### Get Account Balance
```bash
curl http://localhost:8081/accounts/acc-123/balance
```
Expected: `200 OK`
```json
{ "accountId": "acc-123", "balance": 600.00, "currency": "USD" }
```
(500 CREDIT + 200 CREDIT − 100 DEBIT = 600.00)

---

### Get Account Details
```bash
curl http://localhost:8081/accounts/acc-123
```
Expected: `200 OK` with account details and recent transactions

---

## Distributed Tracing

Open Jaeger UI in your browser:

```
http://localhost:16686
```

1. Select `event-gateway` from the **Service** dropdown
2. Click **Find Traces**
3. Open a trace to see the full request span from Gateway → Account Service with propagated `traceId`

---

## Teardown

```bash
docker-compose down
```
