# Event Ledger — Implementation Plan (Remaining Work)

> Purpose: a phased, parallelizable plan for the remaining build, designed so **two dev agents can
> work simultaneously** with no file conflicts. Authoritative sources: `docs/design/*.md`,
> `docs/requirements-breakdown.md`, `.claude/CLAUDE.md`. Test IDs use the canonical **T-1…T-12**
> from `system-overview.md` §10.

---

## 1. Status Snapshot

### Done ✅
- Parent POM + both module POMs (deps resolve, JaCoCo wired)
- `application.yml` + `logback-spring.xml` (both services); Resilience4j CB config (`minimumNumberOfCalls: 10`)
- Dockerfiles, `docker-compose.yml`, `otel-collector-config.yml`
- **Model layer** (compiles + packages):
  - gateway: `EventEntity`, `EventType`, `AuditableEntity`, `JpaConfig`, `GatewayApplication`
  - account: `AccountEntity` (lean), `TransactionEntity` (surrogate PK), `TransactionType`, `AuditableEntity`, `JpaConfig`, `AccountServiceApplication`

### Remaining ⏳
Repositories → DTOs → exceptions/handlers → services → controllers → Gateway client+circuit breaker →
health endpoints → tests (T-1…T-12) → README → end-to-end verification.

---

## 2. Parallelization Strategy (read this first)

```
                    ┌──────────────────────────────────────┐
                    │  PHASE 0 — Contract Freeze (BLOCKING)  │   ← serial, ~15 min, 1 owner
                    │  Lock the Account Service HTTP API +   │
                    │  shared DTO/error/logging conventions  │
                    └───────────────────┬────────────────────┘
                                        │ (contract frozen → fork)
                 ┌──────────────────────┴───────────────────────┐
                 ▼                                               ▼
   ┌──────────────────────────────┐              ┌──────────────────────────────┐
   │  TRACK A — Account Service    │              │  TRACK B — Event Gateway      │
   │  Agent A  (dev-agent)         │   PARALLEL   │  Agent B  (dev-agent)         │
   │  module: account-service/**   │  (no shared  │  module: event-gateway/**     │
   │  builds the REAL service      │   files)     │  builds against WireMock stub │
   └──────────────┬────────────────┘              └───────────────┬───────────────┘
                  │ reviewers (db / java / security)               │ reviewers
                  └───────────────────────┬───────────────────────┘
                                          ▼
                    ┌──────────────────────────────────────┐
                    │  PHASE F — Merge & Integration         │   ← serial, 1 owner
                    │  qa-agent full suite + coverage,       │
                    │  true end-to-end (both up), README     │
                    └──────────────────────────────────────┘
```

**Why this is conflict-free:** Track A only touches `account-service/**`; Track B only touches
`event-gateway/**`. They share *no* Java (separate Maven modules, no shared library). The only
shared files are root-level (`README.md`, `docs/progress.md`) — **owned exclusively by the
orchestrator** at the merge phase. Recommend running each track in its own **git worktree** so the
two agents' `mvn` runs and git indexes never contend.

**Why Track B doesn't wait for Track A:** the Gateway calls the Account Service over HTTP and tests
it with WireMock. As long as the *contract* (Phase 0) is frozen, B stubs the Account Service and
never needs A's code. True end-to-end wiring happens once in Phase F.

---

## 3. PHASE 0 — Contract Freeze  *(blocking gate · 1 owner · ~15 min)*

No code. Ratify the following as **frozen** so both tracks code to the same seam. (All values are
already specified in the design docs — this step just declares them immutable for the build.)

### 3.1 Account Service HTTP contract (the seam Track B stubs)

```
POST /accounts/{accountId}/transactions
  Request : { "eventId", "type":"CREDIT|DEBIT", "amount":>0, "currency", "eventTimestamp" }
  201     : { "accountId", "balance", "currency" }     (new transaction applied)
  200     : { "accountId", "balance", "currency" }     (duplicate eventId — no double-apply)
  400     : validation error shape (see 3.2)

GET /accounts/{accountId}/balance
  200     : { "accountId", "balance", "currency" }
  404     : { "error":"Account not found: {id}" }

GET /accounts/{accountId}
  200     : { "accountId", "balance", "currency", "transactions":[ {eventId,type,amount,eventTimestamp} ] }
            transactions sorted by eventTimestamp DESC (recent first)
  404     : { "error":"Account not found: {id}" }

GET /health  → 200 { "status":"UP", "service":"account-service", "db":"UP" }
```

### 3.2 Shared response conventions (each service keeps its **own copy** — no shared module)

| Shape | JSON | Used for |
|---|---|---|
| Validation error | `{"errors":[{"field":"...","message":"..."}]}` | 400 (Bean Validation + enum/timestamp parse) |
| Generic error | `{"error":"..."}` | 404, malformed body |
| Dependency down | `{"error":"...","code":"DEPENDENCY_UNAVAILABLE"}` | 503 (Gateway only) |

### 3.3 Frozen names (so the two tracks stay consistent)

- Packages: `com.eventledger.account.*`, `com.eventledger.gateway.*`
- Money: `BigDecimal` everywhere; timestamps: `OffsetDateTime`; enums persisted `STRING`
- Trace headers: `traceparent` (auto) **and** explicit `X-Trace-Id` (asserted by T-10)
- Exceptions: `AccountNotFoundException`, `EventNotFoundException`, `ServiceUnavailableException`
  (no `DuplicateEventException` — duplicates return 200, never thrown)

**Exit criteria:** §3.1–§3.3 acknowledged. Fork into Track A and Track B.

---

## 4. TRACK A — Account Service  *(Agent A · `account-service/**`)*

Fully self-contained — no dependency on the Gateway. Build order is strict (each step depends on the
prior). Tests written alongside each step (TDD-friendly).

| Step | Files (under `account-service/src/main/java/com/eventledger/account/`) | Action | Depends on | Risk |
|---|---|---|---|---|
| **A1** | `repository/AccountRepository.java`, `repository/TransactionRepository.java` | `AccountRepository extends JpaRepository<AccountEntity,String>`. `TransactionRepository`: `existsByEventId`, `findByEventId`, **`findByAccountIdOrderByEventTimestampDescEventIdDesc`** (deterministic tie-break — `event_id` is unique, breaks same-timestamp ties), and `@Query("SELECT COALESCE(SUM(t.amount),0) … WHERE accountId=:id AND type=:type")` → `sumAmountByAccountIdAndType` | entities (done) | Low |
| **A2** | `dto/TransactionRequest`, `dto/BalanceResponse`, `dto/AccountResponse`, `dto/TransactionView`, `dto/FieldError`, `dto/ValidationErrorResponse`, `dto/ErrorResponse` (records) | Bean Validation on `TransactionRequest` (`@NotBlank eventId/currency`, `@NotNull type/eventTimestamp`, `@NotNull @DecimalMin("0.01") amount`) | none (parallel with A1) | Low |
| **A3** | `exception/AccountNotFoundException`, `exception/GlobalExceptionHandler` | Handler maps `MethodArgumentNotValidException`→400 errors, `HttpMessageNotReadableException`(enum/`OffsetDateTime`)→400 field error, `AccountNotFoundException`→404, catch-all→500. Shapes per §3.2 | A2 | Med (enum/timestamp branch) |
| **A4** | `service/AccountService.java` | `@Transactional applyTransaction(accountId, req)`: **idempotency first** (`existsByEventId`→return current balance, 200), else auto-create account if absent, save txn, return derived balance. `@Transactional(readOnly=true) getBalance`, `getAccount` (404 if absent). Balance = `sum(CREDIT).subtract(sum(DEBIT))`. MDC put `accountId`/`eventId` | A1, A2 | Med |
| **A5** | `controller/AccountController.java`, `controller/HealthController.java`, `dto/HealthResponse` | `POST /accounts/{id}/transactions` (201/200 via service result), `GET …/balance`, `GET …/{id}`; Health does `SELECT 1` via `JdbcTemplate` | A3, A4 | Low |
| **A6** | `account-service/src/test/java/...` | Unit `AccountServiceTest` (**T-4** balance any-order, auto-create, idempotency safety-net), slice `AccountControllerTest` (txn validation 400s, 201/200, 404), integration `AccountServiceIT` (**T-12** health, apply→balance) | A4, A5 | Med |

**Track A deliverable:** Account Service runs standalone on 8081, `mvn -pl account-service test` green.

---

## 5. TRACK B — Event Gateway  *(Agent B · `event-gateway/**`)*

Depends only on the **frozen contract** (§3), not on Track A's code. The client is exercised with
WireMock. Strict layer order; client/circuit-breaker is the highest-risk piece.

| Step | Files (under `event-gateway/src/main/java/com/eventledger/gateway/`) | Action | Depends on | Risk |
|---|---|---|---|---|
| **B1** | `repository/EventRepository.java` | `extends JpaRepository<EventEntity,String>`; **`findByAccountIdOrderByEventTimestampAscReceivedAtAscEventIdAsc(accountId)`** — primary sort `event_timestamp` ASC, then `received_at` ASC, then unique `event_id` ASC so same-timestamp events list in a stable, deterministic order across calls | entities (done) | Low |
| **B2** | `dto/EventRequest`, `dto/EventResponse`, `dto/TransactionRequest`, `dto/TransactionResponse`, `dto/FieldError`, `dto/ValidationErrorResponse`, `dto/ErrorResponse` | `EventRequest` Bean Validation (all 6 required fields + `@DecimalMin("0.01")`); `metadata` as `Map<String,Object>` on **both** `EventRequest` and `EventResponse` so the 201/200 body round-trips it as a JSON object (matches `system-overview.md` §6.1). Client-side `TransactionRequest/Response` mirror the §3.1 contract | none (parallel with B1) | Low |
| **B3** | `config/RestTemplateConfig.java`, `client/AccountServiceClient.java` | `RestTemplate` bean (base-url `account-service.base-url`) + interceptor adding **`X-Trace-Id`** from `Tracer.currentSpan()`. Client `applyTransaction(...)` annotated `@CircuitBreaker(name="accountService", fallbackMethod="...")`; fallback throws `ServiceUnavailableException` | B2 | **High** (CB + tracing) |
| **B4** | `exception/EventNotFoundException`, `exception/ServiceUnavailableException`, `exception/GlobalExceptionHandler` | Handler: validation→400 errors, `HttpMessageNotReadableException`(enum/timestamp)→400 field error, `EventNotFoundException`→404, `ServiceUnavailableException`→503 `DEPENDENCY_UNAVAILABLE`, `DataIntegrityViolationException`→200 (race), catch-all→500 | B2 | Med |
| **B5** | `service/EventService.java` | `submitEvent(req)`: **idempotency `findById` first** (→200 existing); else `client.applyTransaction` (**before save**); then `save`; catch `DataIntegrityViolationException`→re-read→200. Returns result carrying `created` flag. **On build of `EventEntity`: set `receivedAt = OffsetDateTime.now()` explicitly** (it is `nullable=false` and NOT an audit field) and **serialize `metadata` Map → JSON String** for the `metadata` column (deserialize back to Map when building `EventResponse`). MDC `eventId`/`accountId` | B1, B3, B4 | Med |
| **B6** | `controller/EventController.java`, `controller/HealthController.java`, `dto/HealthResponse` | `POST /events` (201 new / 200 dup / 400 / 503), `GET /events/{id}` (200/404), `GET /events?account=` (200 sorted ASC); Health `SELECT 1` | B5 | Low |
| **B7** | `event-gateway/src/test/java/...` | Unit `EventServiceTest`; slice `EventControllerTest` (**T-5,T-6,T-7** validation); integration w/ WireMock: `IdempotencyIT` (**T-1,T-2**), `OutOfOrderIT` (**T-3**), `CircuitBreakerIT` (**T-8,T-9**), `TracePropagationIT` (**T-10**), `HealthIT` (**T-12**). WireMock via `WireMockExtension` + `@DynamicPropertySource` (NOT `@AutoConfigureWireMock`) | B5, B6 | High |

**Track B deliverable:** Gateway runs on 8080, `mvn -pl event-gateway test` green (Account Service mocked).

> **Optional (design §9, low effort):** in B5 increment a Micrometer counter `events.received.total`
> tagged `type` (CREDIT/DEBIT) and `duplicate` (true/false). `/actuator/prometheus` is already wired
> in `application.yml`, so this is a few lines. Drop if time-boxed — it is not gated by any T-test.

---

## 6. PHASE F — Merge & Integration  *(orchestrator · serial)*

| Step | Action | Owner |
|---|---|---|
| F1 | `mvn clean verify` at root — both modules compile, all *JUnit* tests (T-1…T-10, T-12) pass, JaCoCo line ≥ 80% gate. Note: T-11 is **not** a JUnit test and is verified separately in F2 | qa-agent |
| F2 | **T-11** true end-to-end: `docker-compose up`, then `scripts/e2e-smoke.sh` POSTs an event → GETs balance and asserts it reflects the txn; verify same `traceId` in both services' logs. This is the one required check outside `mvn verify` | orchestrator |
| F3 | `README.md` — build/run/test, API examples, design rationale (link `docs/design/*`), bonus notes | orchestrator |
| F4 | Final sweep: no `double`/`float` on money, no stack traces to clients, health on both, `docs/progress.md` updated | code-reviewer + orchestrator |

---

## 7. Test Matrix (T-1 … T-12)

| ID | Scenario | Track | Type | Test file |
|---|---|---|---|---|
| T-1 | Duplicate POST → 200, same body | B | Integration (WireMock) | `IdempotencyIT` |
| T-2 | Account Service called exactly once on dup | B | WireMock verify | `IdempotencyIT` |
| T-3 | GET /events sorted by `eventTimestamp` ASC (+ include two events with an **identical** `eventTimestamp` and assert a stable, deterministic order via the `received_at`/`event_id` tie-break) | B | Integration | `OutOfOrderIT` |
| T-4 | Balance = Σ(CREDIT) − Σ(DEBIT), any order | **A** | Unit | `AccountServiceTest` |
| T-5 | Missing fields → 400 field error | B | Slice | `EventControllerTest` |
| T-6 | amount ≤ 0 → 400 | B | Slice | `EventControllerTest` |
| T-7 | Unknown `type` → 400 field error | B | Slice | `EventControllerTest` |
| T-8 | Failures → circuit opens → 503 | B | WireMock | `CircuitBreakerIT` |
| T-9 | GET works while circuit OPEN | B | WireMock | `CircuitBreakerIT` |
| T-10 | `X-Trace-Id` (+`traceparent`) sent to Account Svc | B | WireMock verify | `TracePropagationIT` |
| T-11 | Full stack: POST → apply → GET balance | F | E2E (both up — manual/scripted, NOT in `mvn verify`) | `docker-compose` + `scripts/e2e-smoke.sh` |
| T-12 | GET /health UP (both services) | A + B | Integration | `*HealthIT` |

---

## 8. Agent Orchestration

| Stage | Agent | Scope | Gate before proceeding |
|---|---|---|---|
| Phase 0 | orchestrator | freeze §3 | contract acknowledged |
| Track A | **dev-agent #A** (worktree) | `account-service/**` A1→A6 | `mvn -pl account-service test` green |
| Track B | **dev-agent #B** (worktree) | `event-gateway/**` B1→B7 | `mvn -pl event-gateway test` green |
| Review A | database-reviewer → java-reviewer → security-reviewer | A's queries, layering, config | no CRITICAL/HIGH findings |
| Review B | database-reviewer → java-reviewer → security-reviewer | B's client/CB/handler | no CRITICAL/HIGH findings |
| Phase F | qa-agent + orchestrator | root verify, E2E, README | full suite green, coverage gate met |

**Concurrency rule:** Track A and Track B run **at the same time**. Reviewer agents run *after* their
track's gate. Anything touching `README.md` / `docs/progress.md` is orchestrator-only to avoid the
sole conflict surface. Suggested commit prefixes keep history legible: `feat(account):` / `feat(gateway):`.

---

## 9. File Ownership Map (conflict avoidance)

```
account-service/**          → Agent A   (exclusive)
event-gateway/**            → Agent B   (exclusive)
README.md                   → orchestrator (Phase F)
docs/progress.md            → orchestrator (append after each merge)
docs/, .claude/, pom.xml    → frozen during parallel work (change only via orchestrator)
```

No two tracks ever open the same file. Parent `pom.xml` is frozen during Tracks A/B.

---

## 10. Definition of Done

- [ ] `mvn clean verify` green at root; JaCoCo line coverage ≥ 80% per module
- [ ] All 12 tests (T-1…T-12) present and passing
- [ ] Both services boot independently; `GET /health` returns UP with DB status
- [ ] Idempotency (200 on dup, Account Svc called once), out-of-order sort, derived balance correct
- [ ] Circuit breaker opens at ≥50% over 10-call window → 503; GET endpoints unaffected when OPEN
- [ ] `X-Trace-Id` propagated; same `traceId` in both services' JSON logs
- [ ] No `double`/`float` on money; no stack traces leaked to clients
- [ ] `docker-compose up` runs the full stack (bonus: Jaeger trace visible)
- [ ] README complete; `docs/progress.md` updated
```
