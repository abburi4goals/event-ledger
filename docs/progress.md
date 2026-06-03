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
