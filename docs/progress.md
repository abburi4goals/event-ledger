# Event Ledger — Build Progress

## Project Summary

Take-home assessment for a financial institution AI/engineering role.
Two microservices: **Event Gateway** (public, port 8080) and **Account Service** (internal, port 8081).

**Stack:** Java 17 · Spring Boot 3.x · H2 (in-memory) · Resilience4j (circuit breaker) · Micrometer Tracing + OpenTelemetry · Logback JSON · JUnit 5 + WireMock · Maven · Docker Compose

---

## Action Log

### [2026-06-02] Session 1 — Project Bootstrap

**Done:**
- Read and analyzed `Requirements/event-ledger-candidate-handout.md`
- Selected tech stack: Java 17 + Spring Boot 3, H2, Resilience4j circuit breaker
- Initialized git repository (`git init`)
- Created `CLAUDE.md` with full project instructions for AI-assisted development:
  - Architecture and project structure
  - API contracts for both services
  - Coding standards (`BigDecimal` for money, Bean Validation, `@ControllerAdvice`)
  - Key requirements: idempotency, out-of-order tolerance, distributed tracing, circuit breaker config
  - Full test matrix (6 categories: idempotency, out-of-order, validation, circuit breaker, trace propagation, integration)
  - Conventional commit style guide
  - Development commands (build, run, test, Docker Compose)

**Decisions:**
- Circuit breaker (Resilience4j) chosen as resiliency pattern — clearest demo story for interview
- `eventTimestamp` (payload) tracked separately from `receivedAt` (server) — sort/balance always uses `eventTimestamp`
- Duplicate POST returns `200 OK` with original event; Account Service is never called for duplicates

**Next:**
- Scaffold Maven multi-module parent POM
- Build Account Service (simpler, no external service calls)
- Build Event Gateway with AccountServiceClient + circuit breaker
- Write tests
- Docker Compose + README

---

### [2026-06-02] Session 1 — Requirements Breakdown

**Done:**
- Analyzed all 9 requirement areas in depth
- Created `docs/requirements-breakdown.md` covering:
  - Full data model for both services (Event, Transaction, Account entities with field types)
  - Detailed service responsibility split (what each service owns and does NOT own)
  - Complete API contracts with request/response examples and full response matrices
  - Core business rules with edge cases: idempotency race conditions, out-of-order sorting, BigDecimal balance math
  - Input validation matrix (all 7 fields, error messages, HTTP codes)
  - Circuit breaker state machine and all config values with rationale
  - Graceful degradation table (6 scenarios × 2 Account Service states)
  - Full test inventory (12 tests across 6 categories with tooling)
  - 11 edge cases and ambiguity resolutions
  - Implementation priority order

**Key clarifications resolved:**
- POST /events processing flow: validate → idempotency → call Account Service → save → 201. If Account Service fails before save, nothing is persisted (client retries safely thanks to idempotency)
- GET endpoints on Gateway are completely independent of Account Service — always work
- Account Service does its own idempotency (unique constraint on `eventId`) as a safety net
- Balance can go negative — no overdraft protection in scope
- No pagination needed on GET /events?account=X

**Next:**
- Scaffold Maven multi-module parent POM
- Build Account Service
- Build Event Gateway with circuit breaker

---

### [2026-06-02] Session 1 — Agent Setup

**Done:**
- Explored everything-claude-code workspace (63 native agents found)
- Identified and installed 8 agents most relevant to this Java/Spring Boot project under `.claude/agents/`
- All agents adapted from the source originals with project-specific context added

**Agents installed:**

| Agent | Purpose | Model | When to Use |
|---|---|---|---|
| `java-reviewer` | Java/Spring Boot code review | sonnet | After every Java code change |
| `java-build-resolver` | Maven build error fixes | sonnet | When `mvn compile` or `mvn test` fails |
| `tdd-guide` | Test-first development | sonnet | Before writing any new feature code |
| `security-reviewer` | Security + financial data safety | sonnet | After writing controllers, JPA entities, config |
| `code-reviewer` | General code quality review | sonnet | Before every commit |
| `planner` | Phased implementation planning | opus | Before starting any multi-step feature |
| `architect` | Design decisions and ADRs | opus | When evaluating architectural trade-offs |
| `database-reviewer` | JPA/H2 entity and query review | sonnet | When writing entities, repositories, @Query |

**Key adaptations made to each agent:**
- Added Event Ledger project context (service names, ports, package structure)
- java-reviewer: added CRITICAL rule for `double`/`float` monetary fields
- tdd-guide: adapted test commands from `npm test` → `mvn test`, added project-specific test inventory (T-1 to T-8)
- security-reviewer: replaced `npm audit` with OWASP Maven plugin; added financial-system checks
- database-reviewer: rewritten for H2/JPA (original was PostgreSQL-focused); added correct entity/query patterns
- planner/architect: loaded with Event Ledger constraints (BigDecimal, no shared DB, circuit breaker placement)

**Next:**
- Scaffold Maven multi-module parent POM
- Build Account Service
- Build Event Gateway with circuit breaker

---

### [2026-06-02] Session 1 — Dev, QA, and Architect Agent Additions

**Done:**
- Created `dev-agent.md` — full-stack development agent
- Created `qa-agent.md` — quality assurance agent
- Updated `architect.md` — added design document and diagram capabilities

**dev-agent capabilities:**
- End-to-end feature implementation (entity → repo → service → controller → tests)
- Error handling: `GlobalExceptionHandler` with typed exceptions, no stack traces to clients, WARN/ERROR log levels
- Structured logging: Logback JSON with service name, traceId MDC, per-layer log conventions
- Auditing: `AuditableEntity` base class with `@CreatedDate`/`@LastModifiedDate`, `receivedAt` on events
- Git commits: conventional commit format, one logical unit per commit, commit order mirrors build order

**qa-agent capabilities:**
- Creates missing unit tests (Mockito), controller slice tests (`@WebMvcTest`), integration tests (WireMock)
- Runs full test suite: `mvn test`, `mvn jacoco:report`, `mvn jacoco:check`
- Unit test coverage report: per-package breakdown (lines, branches, methods) with 80% threshold gate
- Functional test coverage report: maps all 12 required test cases (T-U1 to T-I4) to pass/fail status
- Quality gate checklist before marking feature complete

**architect agent additions:**
- Design document generation — writes to `docs/design/[feature].md` with 11-section template
- ASCII architecture diagrams at 3 levels: System Context (C4), Service Internals, Sequence flows
- Pre-built diagrams for: happy path, graceful degradation, circuit breaker state machine

**Total agents: 10**

**Next:**
- Scaffold Maven multi-module parent POM
- Build Account Service
- Build Event Gateway with circuit breaker

---

### [2026-06-02] Session 2 — Architecture and Design Documents

**Done:**
- Created docs/design/system-overview.md — system context, service internals, stack ADRs
- Created docs/design/event-processing.md — event flow sequences, idempotency design, data model
- Created docs/design/resiliency-and-observability.md — circuit breaker, tracing, logging, health

**Next:**
- Scaffold Maven multi-module parent POM
- Build Account Service
- Build Event Gateway with circuit breaker

---

### [2026-06-02] Session 2 — Design Review & Flaw Corrections

**Done:**
- Created `.claude/agents/design-reviewer.md` — agent that traces design docs against the handout (R1–R23)
- Ran a full design review of the three design docs + requirements-breakdown against the handout
- Found and corrected 6 contradictions/flaws + 1 documented-assumption risk:

**Corrections (by severity):**
- **F1 (CRITICAL):** Circuit breaker config was missing `minimumNumberOfCalls` — Resilience4j
  defaults it to 100, so with `slidingWindowSize: 10` the circuit would never open and T-8 would
  fail. Added `minimumNumberOfCalls: 10` to the yaml, rationale table, and tightened the T-8 narrative.
- **F2 (HIGH):** Invalid `type` (e.g. "TRANSFER") throws `HttpMessageNotReadableException`
  (InvalidFormatException) before Bean Validation, which system-overview mapped to a generic
  "Malformed request body" — contradicting the promised `{"errors":[{"field":"type",...}]}` body.
  Documented the required GlobalExceptionHandler special-case (enum + OffsetDateTime → field error)
  and reconciled both error tables.
- **F3 (HIGH):** Trace header name was inconsistent (`X-Trace-Id` vs `traceparent`). Micrometer
  sends `traceparent`, not `X-Trace-Id`, so T-10 would fail. Decision (ADR-004): send BOTH —
  `traceparent` (W3C) + explicit `X-Trace-Id` (the header CLAUDE.md mandates / tests assert).
  Reconciled across all four docs.
- **F4 (MEDIUM):** Balance was "stored column" in system-overview but "derived via aggregate query"
  in event-processing. Resolved to **derived-on-read** (immutable ledger is source of truth);
  removed the `balance` column from the accounts table; made the decision definitive.
- **F5 (MEDIUM):** requirements-breakdown said Transaction `eventId` is PK; entity uses surrogate
  `Long id` PK + unique `eventId`. Fixed the table.
- **F6 (LOW):** 503 body in requirements-breakdown was missing `"code":"DEPENDENCY_UNAVAILABLE"`. Added.
- **F7 (assumption):** R17 (balance-query degradation) — Gateway has no balance proxy by design;
  rewrote the open question to own this as an explicit, justified scoping decision.
- Also specified GET /accounts/{id} transaction sort order (eventTimestamp DESC, "recent first").

**Note:** CLAUDE.md's circuit-breaker line also omits `minimumNumberOfCalls`; the design docs now
specify it. Worth carrying into the eventual `application.yml`.

**Next:**
- Build Account Service (apply derived-balance + InvalidFormatException handling decisions)
- Build Event Gateway with circuit breaker (set `minimumNumberOfCalls: 10`, send both trace headers)

---

### [2026-06-02] Session 3 — Project Scaffold

**Done:**
- Created Maven multi-module parent POM (`pom.xml`) — Spring Boot 3.3.4, Java 17, Resilience4j BOM,
  logstash-logback-encoder 7.4, WireMock 3.6, JaCoCo 0.8.12 (80% line coverage gate)
- Created `event-gateway/pom.xml` with all module dependencies:
  spring-boot-starter-web/data-jpa/validation/actuator/aop, H2, Resilience4j (spring-boot3 + micrometer),
  Micrometer tracing bridge OTel, opentelemetry-exporter-otlp, micrometer-registry-prometheus,
  logstash-logback-encoder, WireMock (test)
- Created `account-service/pom.xml` — same stack minus Resilience4j and WireMock (no outbound calls)
- Created full package directory trees for both services (controller/service/repository/model/dto/exception;
  test packages: controller/service/integration)
- Created `application.yml` for both services:
  - event-gateway: port 8080, Resilience4j config with minimumNumberOfCalls: 10 (matches slidingWindowSize)
  - account-service: port 8081
- Created `logback-spring.xml` for both services (Logstash JSON encoder, service name + traceId MDC)
- Created `event-gateway/Dockerfile` and `account-service/Dockerfile` (multi-stage: Maven build → JRE alpine)
- Created `docker-compose.yml` — event-gateway, account-service, otel-collector, jaeger; health-check dependency
- Created `otel-collector-config.yml` — OTLP receiver → Jaeger exporter pipeline

**Decisions:**
- Parent uses `spring-boot-starter-parent` (not BOM import) so plugin defaults (Surefire, compiler) are inherited
- `-parameters` compiler flag added so Spring MVC can bind `@PathVariable` / `@RequestParam` by name
- Resilience4j `minimumNumberOfCalls: 10` set explicitly (design review F1 fix) — without it the circuit never opens
- OTel exporter endpoint defaults to localhost:4318 (overridden by env var in Docker Compose)
- Jaeger bonus included in docker-compose (requirement bonus item 4)

**Next:**
- Build Account Service (models → repository → service → controller → exception handler)
- Build Event Gateway (same order, plus AccountServiceClient + circuit breaker + X-Trace-Id interceptor)

---

### [2026-06-02] Session 3 — Implementation Plan (parallel multi-agent)

**Done:**
- Created `docs/implementation-plan.md` — phased plan for the remaining build, structured for
  **parallel development by two dev agents**.
- Core strategy: Phase 0 freezes the Account Service HTTP contract + shared conventions (blocking
  gate), then **Track A (account-service) ∥ Track B (event-gateway)** run concurrently with zero
  file overlap (separate Maven modules; Gateway stubs Account Service via WireMock), then Phase F
  merges (root verify, true E2E, README).
- Includes: step tables with exact file paths/deps/risk per track, frozen contract spec, shared
  error/logging conventions, T-1…T-12 test matrix mapped to track+file+type, agent-orchestration
  table with review gates, file-ownership map (conflict avoidance), and Definition of Done.

**Decisions:**
- Standardized on canonical test IDs **T-1…T-12** (from system-overview §10) — resolves the earlier
  T-U/T-C vs T-1..T-8 numbering drift across agents.
- Recommend git **worktree** isolation per track so the two agents' mvn/git never contend.
- Root files (`README.md`, `docs/progress.md`, `pom.xml`) are orchestrator-only during parallel work.

**Next (per the plan):**
- Phase 0 contract freeze → dispatch Track A + Track B in parallel

---

### [2026-06-02] Session 3 — Entity Scaffold (Model Layer)

**Done:**
- Scaffolded the full JPA model layer for both services (9 files):
  - **event-gateway** `model/`: `EventType` enum, `AuditableEntity` (@MappedSuperclass,
    createdAt/updatedAt), `EventEntity` (eventId @Id = idempotency key, account_id index,
    BigDecimal(19,4) amount, eventTimestamp vs received_at, metadata TEXT) + `config/JpaConfig`
    (@EnableJpaAuditing)
  - **account-service** `model/`: `TransactionType` enum, `AuditableEntity`, `AccountEntity`
    (lean — account_id + currency only, NO balance column), `TransactionEntity` (surrogate Long id
    PK + unique event_id, BigDecimal(19,4) amount) + `config/JpaConfig`
- Followed the reviewed design decisions: derived balance (no stored column), surrogate transaction
  PK with unique eventId (F5), OffsetDateTime for all timestamps, @Enumerated(STRING), natural-key
  equals/hashCode, protected no-arg constructors for JPA.

**Caveats / not yet done:**
- **Build not verified** — no JDK/Maven runtime available in this environment, so `mvn compile`
  could not be run. Entities written carefully but compilation is unverified.
- `@EnableJpaAuditing` (JpaConfig) only activates once each service has a `@SpringBootApplication`
  main class to component-scan it. Until then audit fields won't populate at runtime.
- Schema does not materialize until main app classes + repositories exist to boot the JPA context
  (ddl-auto=create-drop generates tables at startup).

**Next:**
- Add `@SpringBootApplication` main classes for both services
- Repositories (EventRepository with OrderByEventTimestampAsc; Account/Transaction repos with
  balance sum query) → then service/controller layers

---

### [2026-06-02] Session 3 — qa-agent / tdd-guide / database-reviewer Review

**Done:**
- Reviewed the three remaining build/test agents against the design docs + scaffolded POM.
- **qa-agent.md (had a blocker):**
  - **BLOCKER** — templates used `@AutoConfigureWireMock` (from `spring-cloud-contract-wiremock`,
    which is NOT in the POM — POM uses `wiremock-standalone`). Rewrote all integration/circuit
    templates to the JUnit 5 `WireMockExtension` + `@DynamicPropertySource` pattern so they compile.
  - Fixed a broken unit-test template (an `AccountService` balance test living inside
    `EventServiceTest`, referencing undeclared mocks) — split into a proper `AccountServiceTest`.
  - Corrected the circuit-breaker template: 10-call window / ≥50% rate (not ">=5 failures"); added
    the design's T-8 assertion that WireMock is NOT called once the circuit is OPEN.
  - Reconciled the coverage claim — POM enforces **line** 80% only; branch is a tracked target, not
    a build-failing gate (agent previously claimed both were enforced).
- **tdd-guide.md:** fixed the wrong circuit threshold ("five consecutive failures") in the T-5
  comment and in the "Good" test-name example → "failure rate exceeds threshold (10-call window)";
  added the same WireMock-standalone wiring note.
- **database-reviewer.md (was the cleanest):** hardened the Account Service DB section to state
  `accounts` is lean (accountId + currency, NO stored balance column) and `transactions` uses a
  surrogate `Long id` PK with `event_id` as the unique key (F4/F5 decisions); tightened the
  BigDecimal checklist to "computed balance" rather than implying a stored balance field.

**Next:**
- Build Account Service (models → repository → service → controller → exception handler)
- Build Event Gateway (same order, plus AccountServiceClient + circuit breaker + X-Trace-Id interceptor)

---

### [2026-06-02] Session 3 — dev-agent ↔ Design Doc Reconciliation

**Done:**
- Reviewed `.claude/agents/dev-agent.md` against all three design docs + reconciled 8 gaps/conflicts
  that would have produced code contradicting the reviewed design (and failing T-7, T-8, T-10):
  - **CRITICAL** — Added the ordering invariant (Account Service called BEFORE Gateway DB save)
  - **CRITICAL** — Added `minimumNumberOfCalls: 10` to the CB guidance; fixed the wrong
    "circuit opens after 5 consecutive failures" wording → "≥50% failure rate over the 10-call window"
  - **HIGH** — Added dual trace-header guidance (`traceparent` + explicit `X-Trace-Id` via a
    `ClientHttpRequestInterceptor` on the Gateway RestTemplate)
  - **HIGH** — Added `HttpMessageNotReadableException`/`InvalidFormatException` handler
    (enum/`OffsetDateTime` → field-error body) to the GlobalExceptionHandler example
  - **HIGH** — Rewrote the handler so response bodies match the documented contract shapes
    (`{"errors":[...]}`, `{"error":"..."}`, `{"error":"...","code":"DEPENDENCY_UNAVAILABLE"}`)
    instead of the old `ErrorResponse("VALIDATION_FAILED", ...)` wrapper
  - **MEDIUM** — Renamed `AccountServiceException` → `ServiceUnavailableException`; removed the
    spurious `DuplicateEventException` (duplicates return 200, not a thrown exception); added
    `AccountNotFoundException`
  - **MEDIUM** — Reconciled audit fields with the lean derived-balance Account model
    (AccountEntity = accountId + currency only, NO stored balance, NO lastTransactionAt)
  - **LOW** — Added custom `GET /health` controller to the implementation checklist
- Added a new "Cross-Service Integration" section to dev-agent (CB config + fallback + X-Trace-Id
  interceptor) — previously the agent had no guidance for the `client/` and `config/` packages
- Expanded the implementation checklist with the above as explicit gates

**Next:**
- Build Account Service (models → repository → service → controller → exception handler)
- Build Event Gateway (same order, plus AccountServiceClient + circuit breaker + X-Trace-Id interceptor)

---

## Session 4 — Implementation Plan Validation (2026-06-03)

**Done:**
- Validated `docs/implementation-plan.md` against all 3 design docs + the committed entity code.
  Confirmed accurate: frozen Account Service contract (§3.1 ↔ system-overview §6.2), error shapes
  (§3.2 ↔ §8), T-1…T-12 matrix (↔ §10), the call-before-save ordering invariant (B5), and the
  idempotency-race handling (DataIntegrityViolation → 200) end-to-end.
- Fixed 4 genuine gaps the plan was silent on (entity-forced details that would block an agent):
  1. **B5** — must set `receivedAt = now()` explicitly (it is `nullable=false` and NOT an audit field).
  2. **B2/B5** — `metadata` is `Map<String,Object>` on request AND response; service serializes
     Map↔JSON String for the `metadata` column so the body round-trips per §6.1.
  3. **T-11** — clarified it is a manual/scripted compose check (`scripts/e2e-smoke.sh`), NOT part of
     `mvn verify`; updated F1/F2 and the test matrix so no agent assumes `mvn verify` covers it.
  4. **Track B** — added optional `events.received.total` Micrometer counter (design §9), clearly
     marked droppable / not gated by any T-test.

**Decisions:**
- Plan is APPROVED to execute as written (with the 4 fixes applied). No structural changes needed —
  parallelization strategy (Phase 0 freeze → Track A ∥ Track B → Phase F merge) is sound.

**Next:**
- Phase 0 contract freeze (ratify §3, ~15 min, no code) → dispatch Track A + Track B in parallel.

---

## Session 5 — Out-of-Order Ordering Determinism Fix (2026-06-03)

**Done:**
- Reviewed out-of-order handling end-to-end. Confirmed the core design is correct: events stored
  in arrival order with separate `eventTimestamp`/`receivedAt`, sorted on read by `eventTimestamp`,
  balance = Σ(CREDIT) − Σ(DEBIT) (commutative, order-independent).
- Found + fixed a genuine flaw: sort was keyed on `eventTimestamp` ALONE, so same-millisecond
  events (common in mainframe/payment-network batch replays) had non-deterministic listing order
  across calls and across H2 vs. prod RDBMS. Balance was never affected — listing stability only.
- Added deterministic tie-break keys:
  - Gateway: `findByAccountIdOrderByEventTimestampAscReceivedAtAscEventIdAsc`
    (`ORDER BY event_timestamp ASC, received_at ASC, event_id ASC`).
  - Account Service: `findByAccountIdOrderByEventTimestampDescEventIdDesc`
    (no `received_at` column there; unique `event_id` is the tie-break).
- Strengthened T-3 in the plan to POST two identical-timestamp events and assert stable order.
- Updated 3 docs: `implementation-plan.md` (A1/B1 + T-3), `event-processing.md` (repo snippet +
  Out-of-Order section with the rationale + UTC-normalization note), `system-overview.md` (§6.2 DESC
  history tie-break).

**Decisions:**
- `event_id` (unique) is the final tie-break guaranteeing total ordering; `received_at` is the
  intuitive secondary key for the Gateway ("same instant → ingestion order").

**Next:**
- Phase 0 contract freeze → dispatch Track A + Track B in parallel (plan now validated + corrected).

---

## Session 6 — Full Implementation: Track A + Track B (2026-06-03)

**Done:**
- Implemented Account Service end-to-end (A1–A6):
  - `AccountRepository`, `TransactionRepository` (balance JPQL + deterministic DESC sort)
  - 8 DTOs as Java 17 records (`TransactionRequest` with Bean Validation, `BalanceResponse`,
    `AccountResponse`, `TransactionView`, `FieldError`, `ValidationErrorResponse`, `ErrorResponse`,
    `HealthResponse`)
  - `AccountNotFoundException` + `GlobalExceptionHandler` — exact contract shapes for 400/404/500;
    `HttpMessageNotReadableException` → `InvalidFormatException` → field-error shape for
    enum/OffsetDateTime deserialization failures
  - `AccountService` — idempotency-first (`existsByEventId`), auto-create account on first txn,
    derived balance `Σ(CREDIT)−Σ(DEBIT)` via JPQL SUM, `ApplyResult(balance, created)` flag for
    201 vs 200 at controller layer
  - `AccountController` (POST 201/200, GET balance, GET account), `HealthController` (SELECT 1)
  - 16 tests green: 6 unit (Mockito), 6 controller slice (@WebMvcTest), 4 integration
    (@SpringBootTest); covers T-4 (balance correctness) and T-12 (health)

- Implemented Event Gateway end-to-end (B1–B7):
  - `EventRepository` — 3-key deterministic sort: `eventTimestamp ASC, received_at ASC, event_id ASC`
  - 8 DTOs (`EventRequest` Bean Validation all 6 fields, `EventResponse`, `TransactionRequest/Response`,
    `FieldError`, `ValidationErrorResponse`, `ErrorResponse`, `HealthResponse`)
  - `RestTemplateConfig` — `ClientHttpRequestInterceptor` adds `X-Trace-Id: span.context().traceId()`
    alongside Micrometer's auto-propagated W3C `traceparent`
  - `AccountServiceClient` — `@CircuitBreaker(name="accountService")` with fallback throwing
    `ServiceUnavailableException`; `minimumNumberOfCalls: 10` already set in `application.yml`
  - `GlobalExceptionHandler` — same field-error shape for enum/OffsetDateTime; 503 with
    `DEPENDENCY_UNAVAILABLE` code; no stack traces to clients
  - `EventService` — idempotency `findById` FIRST; Account Service called BEFORE Gateway DB save
    (ordering invariant); `receivedAt = OffsetDateTime.now()` set explicitly (not an audit field);
    metadata `Map<String,Object>` ↔ JSON String via ObjectMapper; `DataIntegrityViolationException`
    → re-read → 200 (concurrent duplicate race)
  - `EventController` (POST 201/200/400/503, GET /{id} 200/404, GET ?account= sorted), `HealthController`
  - 19 tests green: 4 unit (Mockito), 7 controller slice (@WebMvcTest), WireMock integration:
    `IdempotencyIT` (T-1, T-2), `OutOfOrderIT` (T-3 + tie-break stability), `CircuitBreakerIT`
    (T-8, T-9), `TracePropagationIT` (T-10), `HealthIT` (T-12 Gateway)

- Full root build: `mvn clean test` → **35/35 tests passing, BUILD SUCCESS** (both modules)

**Decisions / Infrastructure fixes for JDK 26 runtime:**
- JaCoCo `includes: com/eventledger/**` in parent POM — prevents agent from trying to instrument
  JDK 26 bootstrap classes (class file version 70, unsupported by ASM in JaCoCo 0.8.12)
- Surefire `--add-opens java.base/...=ALL-UNNAMED` flags for Mockito instrumentation
- `mockito-extensions/org.mockito.plugins.MockMaker = mock-maker-subclass` in both modules
  (Byte Buddy inline mock maker fails on JDK 26 without dynamic attach; subclass mocking works)
- `DateTimeProvider` bean in both `JpaConfig` classes returning `OffsetDateTime.now()` — Spring
  Data JPA auditing defaults to `LocalDateTime`, which Hibernate 6 cannot convert to `OffsetDateTime`
- `spring.jpa.properties.hibernate.jdbc.time_zone: UTC` in both `application.yml` files

**Next:**
- Review pass: code-reviewer + security-reviewer agents (Phase F reviewers)
- `mvn clean verify` to confirm JaCoCo coverage gate
- README.md
- T-11 end-to-end smoke test via `docker-compose up`

---

## Session 7 — API Key Authentication on Event Gateway (2026-06-03)

**Done:**
- Implemented `ApiKeyAuthFilter` (`OncePerRequestFilter`, `@Order(1)`) in
  `com.eventledger.gateway.filter`:
  - Checks `X-Api-Key` request header against `gateway.api-key` property on every request
  - Returns `401 {"error":"Invalid or missing API key","code":"UNAUTHORIZED"}` if missing or wrong
  - Skips `/health` via `shouldNotFilter()` — health endpoint stays publicly accessible for
    Docker health checks and ops tooling
  - Logs `WARN` with request URI on rejection; traceId present in MDC at that point
- Added `gateway.api-key: ${GATEWAY_API_KEY:test-api-key-secret}` to `application.yml`
  - Production: override with `GATEWAY_API_KEY` environment variable
  - Local dev / tests: default `test-api-key-secret` (no test-specific config needed)
- Added `ApiKeyFilterIT` (6 tests): missing key → 401, wrong key → 401, GET no key → 401,
  `/health` with no key → 200, valid key passes to endpoint (non-401 response)
- Updated all existing tests that hit protected endpoints to include `X-Api-Key` header:
  `IdempotencyIT`, `OutOfOrderIT`, `CircuitBreakerIT`, `TracePropagationIT`, `EventControllerTest`
  (`HealthIT` untouched — only hits `/health`)
- Full test suite: **36/36 tests passing, BUILD SUCCESS** (both modules)

**Decisions:**
- Account Service deliberately left open (no auth changes) — it is internal-only and not
  publicly reachable in the Docker Compose network
- `@Order(1)` ensures the filter runs before all other filters (including tracing and logging)
- `/health` excluded from auth — required for Docker `healthcheck:` and load balancer probes
- Default property value `test-api-key-secret` doubles as the test constant, so no
  `src/test/resources/application.yml` override is needed (avoids Maven classpath shadowing
  the main `application.yml`)

**Next:**
- Review pass: code-reviewer + security-reviewer agents (Phase F reviewers)
- `mvn clean verify` to confirm JaCoCo coverage gate
- README.md
- T-11 end-to-end smoke test via `docker-compose up`

---

## Session 8 — Rate Limiting on Event Gateway (2026-06-03)

**Done:**
- Implemented `RateLimitFilter` (`OncePerRequestFilter`, `@Order(2)`) in
  `com.eventledger.gateway.filter`:
  - Per-client-IP rate limiting using a `ConcurrentHashMap<String, RateLimiter>` — each IP
    gets its own Resilience4j `RateLimiter` instance, created lazily on first request
  - Default limit: 60 requests per second per IP (overridable via `GATEWAY_RATE_LIMIT_RPS`)
  - Exceeding the limit returns `429 Too Many Requests` with:
    - `Retry-After: 1` header (matches the 1-second refresh period)
    - JSON body `{"error":"Rate limit exceeded","code":"TOO_MANY_REQUESTS"}`
  - Skips `/health` via `shouldNotFilter()` — health probes must never be throttled
  - Respects `X-Forwarded-For` header for proxied traffic (first IP in chain used as key)
  - Logs `WARN` with client IP and URI on each rejection (traceId in MDC)
- Added `gateway.rate-limit.requests-per-second: ${GATEWAY_RATE_LIMIT_RPS:60}` to
  `application.yml`
- Added `RateLimitIT` (4 tests, `@TestPropertySource(properties = "gateway.rate-limit.requests-per-second=3")`):
  - Burst of 10 requests exceeds the 3/sec limit → at least some return 429
  - Rate-limited response carries `Retry-After: 1` and `TOO_MANY_REQUESTS` body
  - `/health` endpoint never rate-limited regardless of burst
  - `X-Forwarded-For` key: a distinct fake IP gets its own fresh 3-req budget
- Full test suite: **34/34 tests passing, BUILD SUCCESS** (event-gateway)

**Decisions:**
- `@Order(2)` — runs after `ApiKeyAuthFilter` (`@Order(1)`): unauthenticated requests are
  rejected first, so rate limit counters are only consumed by valid API key holders
- Per-IP (not global) rate limiting — prevents a single abusive client from starving others
- Resilience4j `RateLimiter` reused (already a project dependency) — consistent with the
  circuit breaker pattern; no new library needed
- `ConcurrentHashMap.computeIfAbsent` for thread-safe lazy per-IP instance creation
- `X-Forwarded-For` support included — load balancers/proxies mask the real client IP in
  `remoteAddr`; using the forwarded header is standard for edge rate limiters
- In-memory map grows with distinct IPs — acceptable for this service's known client base;
  production upgrade path is Caffeine TTL cache or Redis

**Next:**
- Review pass: code-reviewer + security-reviewer agents (Phase F reviewers)
- `mvn clean verify` to confirm JaCoCo coverage gate
- README.md
- T-11 end-to-end smoke test via `docker-compose up`
