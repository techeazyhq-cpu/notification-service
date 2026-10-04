---
name: developer
description: Use for coding tasks in Java, Python, React, SQL, PL/SQL, shell, Docker, Terraform, CI/CD. TDD, clean code.
---

# Developer standards

Audience: a senior software architect (18+ years) who codes in Java, Python, React, SQL, PL/SQL, and shell.
Be concise, skip basics, and explain trade-offs. Existing project conventions (CLAUDE.md, linters, formatters)
override these defaults. For architecture and design decisions, use the solutioning skill.

## Non-negotiable rules (all languages)

1. **Test Driven Development, always.** Follow red, green, refactor for every change:
   - Write a failing test first and confirm that it fails for the right reason.
   - Write the minimum code that makes it pass.
   - Refactor with the tests green, then repeat in small steps.
   - Show the test before the implementation. Do not write production code without a test that demands it.
   - Bug fixes start with a test that reproduces the bug.
2. **Strict clean code.**
   - Use descriptive, intention-revealing names for everything: variables, references, parameters, methods,
     classes, interfaces, packages, modules, tables, columns, files, and tests. Use full words.
   - No cryptic abbreviations or single-letter names (except conventional loop indices in tiny scopes).
   - Names come from the ubiquitous language of the domain.
   - Small functions with a single responsibility, no duplication, no dead code, no magic numbers, no commented-out
     code. Comments explain why, never what.
   - Prefer few parameters, early returns, and no deep nesting.
3. **Maximum line length is 120 characters** in every language and file type (code, SQL, shell, YAML, HCL,
   Markdown, configuration). Configure the formatter and linter to enforce it, and add it to `.editorconfig`
   (`max_line_length = 120`). Wrap long lines sensibly rather than shortening names.

## Working method

1. **Understand first.** Read the surrounding code and tests before changing anything. Confirm requirements, inputs,
   outputs, and edge cases, and state assumptions.
2. **Plan small.** Prefer small, reviewable changes and minimal diffs. Ask before large refactors, renames across
   modules, or dependency upgrades.
3. **Run it.** Run tests, linter, formatter, and type checker where the environment allows, and report the result.
   Never claim code works if it was not run.
4. **Explain briefly.** Summarise what changed, key decisions, how to run the tests, and any risks or follow-ups.
5. **Ask before destructive actions:** dropping or altering data, migrations on shared databases, deleting files,
   force pushes, `terraform apply` or `destroy`, and production deployments.

Deliver complete, runnable code, not fragments, unless a snippet is requested.

## Design in code

- **Clean architecture:** domain at the centre, then application (use cases), then adapters, then infrastructure.
  Dependencies point inward. Domain code has no framework, database, or transport imports.
- **DDD tactical patterns:** aggregates that enforce invariants, immutable value objects, domain events,
  repositories as ports, and domain services. Keep transaction boundaries at the aggregate.
- **Ports and adapters** at every boundary (databases, messaging, external APIs, FHIR). Map between external and
  domain models in an anti-corruption layer. Never expose entities or persistence models through APIs.
- **Event-driven code:** idempotent consumers, outbox pattern for publishing, explicit event schemas and versions,
  dead-letter handling, and correlation IDs on every message.
- **Sagas:** explicit state, compensating actions, timeouts, and tests for failure paths.
- Fail fast with explicit, typed errors. Never swallow exceptions silently.

## Design principles and patterns (apply what fits the problem)

For every problem, actively identify and apply the relevant design principles, coding standards, and design patterns.
Do not work from a fixed list. Before coding, ask what varies, what must stay stable, and which forces are in play,
then choose accordingly. State the principles and patterns used, and why, in one or two lines of the explanation.

**Guardrails against over-engineering:**
- A pattern must solve a problem that exists now (YAGNI). If a plain function or class is enough, use it.
- Apply the rule of three before abstracting. Prefer the simplest design that meets the requirement.
- Prefer language features (lambdas, records, sealed types, protocols) over heavy pattern scaffolding.
- Let tests and refactoring reveal the pattern. Do not force a pattern in advance.

### Principles

- SOLID: single responsibility, open-closed, Liskov substitution, interface segregation, dependency inversion.
- DRY (one source of truth for each piece of knowledge, not just identical text), KISS, and YAGNI.
- Separation of concerns, high cohesion, low coupling, encapsulation, and programming to interfaces.
- Composition over inheritance, the Law of Demeter, and tell, don't ask.
- Command-query separation, principle of least astonishment, fail fast, and design by contract.
- GRASP: information expert, creator, controller, low coupling, high cohesion, polymorphism, protected variations.
- Immutability by default, idempotency for retried operations, and least privilege.
- The twelve-factor app for services: configuration in the environment, stateless processes, logs as streams.

### Software design patterns (GoF): creational, structural, behavioural

Know all 23 Gang of Four patterns and apply the ones the problem calls for. Introduce a pattern during the refactor
step of TDD, typically when a second variant appears, a conditional keeps growing, or the code is hard to test
without a seam. Name the pattern in the class or in the explanation so intent is clear.

**Creational** (how objects are created):
- **Factory Method:** a subclass or method decides which concrete type to create. Use when creation varies by type
  or configuration and callers should depend on an interface.
- **Abstract Factory:** create families of related objects that must be used together (for example a database
  dialect with its connection, dialect, and query builder).
- **Builder:** construct complex objects step by step. Use for many optional parameters, immutable objects, and
  test data builders.
- **Prototype:** copy an existing object instead of building a new one (copy constructors, records with modified
  copies).
- **Singleton:** avoid the static version. Let the dependency injection container manage a single-instance scope.

**Structural** (how objects are composed):
- **Adapter:** make an incompatible interface fit the one you need. Use for third-party libraries, legacy systems,
  and external APIs behind a port.
- **Bridge:** separate an abstraction from its implementation when two dimensions vary independently.
- **Composite:** treat single objects and groups uniformly in a tree (menus, rule trees, organisation hierarchies).
- **Decorator:** add behaviour dynamically without subclass explosion (logging, caching, retry, validation wrappers).
- **Facade:** offer a simple interface over a complex subsystem.
- **Flyweight:** share large amounts of identical immutable data to save memory. Use only when measured.
- **Proxy:** control access to an object: lazy loading, caching, access control, or a remote stand-in.

**Behavioural** (how objects interact and share responsibility):
- **Strategy:** interchangeable algorithms behind one interface. Often replaces long `if`/`switch` chains.
- **State:** behaviour changes with internal state (workflows, saga steps, order lifecycle).
- **Observer:** notify many dependents of a change (domain events, listeners, publish-subscribe).
- **Command:** encapsulate a request as an object for queuing, logging, retrying, or undo. Pair with **Memento**
  to capture and restore state for undo.
- **Chain of Responsibility:** pass a request along handlers (validation pipelines, filters, middleware).
- **Template Method:** a fixed algorithm skeleton with overridable steps. Prefer Strategy and composition when
  inheritance would be rigid.
- **Visitor:** add operations over a stable object structure. In Java 21, sealed types with pattern matching are
  often simpler.
- **Mediator:** centralise complex many-to-many communication between objects.
- **Iterator:** traverse a collection without exposing its structure. Use the language's built-in support.
- **Interpreter:** evaluate a small grammar or rule language. Use for simple domain-specific rules only.

**Selection guide (symptom, then pattern):**
- Growing `if`/`switch` on type or mode: Strategy, State, or plain polymorphism.
- Constructor with many parameters or optional values: Builder.
- Caller should not know the concrete type it gets: Factory Method or Abstract Factory.
- Third-party or legacy interface does not fit: Adapter. Complicated subsystem: Facade.
- Need to add behaviour without subclass explosion: Decorator.
- Need lazy loading, caching, or access control: Proxy.
- Part-whole tree: Composite. Two independent dimensions of variation: Bridge.
- Many parties react to a change: Observer. Request must be queued, logged, retried, or undone: Command.
- Sequence of optional processing steps: Chain of Responsibility.
- Same algorithm skeleton with varying steps: Template Method or Strategy.

**Also common in code:** Dependency Injection, Repository, Unit of Work, Specification, Null Object, and resilience
patterns (circuit breaker, retry with backoff, timeout, bulkhead) using established libraries such as Resilience4j.
Architecture-level patterns (CQRS, saga, outbox, BFF, strangler fig) belong to the solutioning skill.

### Language idioms for patterns

- **Java 21:** sealed interfaces, records, and pattern matching for algebraic data types (often better than Visitor).
  Lambdas and functional interfaces for Strategy and Command. Streams for pipelines, used readably.
- **Python:** protocols for structural typing, decorators, context managers, generators, and dataclasses.
- **React:** composition, custom hooks, container and presentational split, compound components, context providers,
  and error boundaries.
- **SQL and PL/SQL:** set-based thinking, views and packages as encapsulation, and constraints as invariants.
- **Shell:** small functions, pipelines, and `trap` for cleanup.

### Smells and anti-patterns to avoid

God class, anemic domain model where the domain is rich, service locator, Singleton abuse, primitive obsession,
feature envy, shotgun surgery, long parameter lists, stringly-typed code, leaky abstractions, distributed monolith,
golden hammer, premature optimisation, and cargo-cult patterns. Use the refactoring catalogue during the refactor
step of TDD to remove smells safely, with the tests green.

## Security and data protection

- Validate all input at the boundary. Use parameterised queries only, never string-built queries in any language.
- No secrets, keys, or credentials in code, logs, images, or version control. Use environment variables or a
  secrets manager.
- Treat personal data and PHI as toxic: never log it, keep it out of events and URLs, mask it in test data, and
  encrypt it at rest and in transit. Support erasure and retention (GDPR, HIPAA, DPDPA, Middle East rules).
- Follow OWASP Top 10 practices. Scan dependencies and pin versions. Apply least privilege everywhere.

## Healthcare code

- Use HL7 FHIR at system boundaries only. Keep FHIR resources out of the core domain model and map to and from it
  in adapters.
- Use established libraries (HAPI FHIR in Java, `fhir.resources` in Python, a typed FHIR library in TypeScript)
  and validate resources against the required profiles in tests.
- Use standard code systems (SNOMED CT, LOINC, ICD) and never hard-code local codes without a mapping.

## Java 21 and Spring Boot

- Java 21. Use records for value objects and DTOs, sealed types and pattern matching where they clarify the model,
  and `Optional` for return values only. Constructor injection only, no field injection. Prefer immutability.
- Maven, using the Maven wrapper, the Spring Boot BOM, and dependency management. Use the enforcer plugin,
  Surefire for unit tests and Failsafe for integration tests, and JaCoCo for coverage.
- Package by feature or bounded context with a hexagonal layout, not by technical layer.
- **Choose the web stack deliberately:**
  - Spring Web (MVC) for blocking I/O. Consider virtual threads for high concurrency.
  - Spring WebFlux only when the whole call chain is non-blocking (reactive drivers and clients).
  - Never block inside reactive pipelines. Do not mix blocking JPA or JDBC calls into WebFlux code.
  - Propagate context (correlation IDs) through the Reactor context.
- JPA: keep entities inside the persistence adapter, respect aggregate boundaries, avoid N+1 queries, and manage
  schema with Flyway or Liquibase. Use R2DBC or reactive drivers with WebFlux.
- Logging with SLF4J and parameterised messages. Handle errors with a global exception handler
  (`@RestControllerAdvice`) that returns a consistent problem-details response.
- **TDD tools:** JUnit 5, AssertJ, Mockito (for ports only), `@WebMvcTest` or `@WebFluxTest` slices,
  `WebTestClient`, Reactor `StepVerifier`, Testcontainers for real databases and brokers, and ArchUnit to enforce
  layer rules. Enforce formatting with Spotless and rules with Checkstyle (`LineLength` max 120).

## Python (Flask and FastAPI)

- Python 3.11+, full type hints checked with mypy or pyright. Format and lint with ruff (`line-length = 120`).
- Use frozen `dataclasses` or Pydantic models for value objects and validation. No mutable default arguments.
  Use context managers for resources.
- `pyproject.toml` with an isolated environment (uv, poetry, or venv). Pin dependencies.
- **FastAPI:** async endpoints only when the I/O is truly async. Use dependency injection for ports, Pydantic
  request and response schemas, routers per bounded context, and lifespan events for start-up and shutdown.
- **Flask:** application factory, blueprints per bounded context, and configuration objects per environment.
  Run behind a production server (Gunicorn or similar), never the development server.
- Keep the domain layer free of Flask and FastAPI imports.
- **TDD tools:** pytest with fixtures and parametrisation, the framework test client (`TestClient` for FastAPI,
  Flask test client), `pytest-cov`, and mocks limited to boundaries.

## React with Vite (TypeScript)

- TypeScript in strict mode. Functional components and hooks only. No `any` without a comment explaining why.
- Vite for build and dev server. Environment variables exposed with the `VITE_` prefix are public, so never put
  secrets in them. Use code splitting and lazy routes.
- Structure by feature aligned with the bounded context or micro frontend. Keep components small and
  presentational, with logic in hooks and services.
- State: server state with TanStack Query (or equivalent), minimal local state, and a global store only when
  clearly needed.
- Accessibility (WCAG) by default. Support i18n and RTL and Arabic layouts where relevant.
- Security: no `dangerouslySetInnerHTML` with untrusted content, safe token handling (prefer httpOnly cookies),
  and no PHI or personal data in localStorage, URLs, or logs.
- **TDD tools:** Vitest with React Testing Library (test behaviour, not implementation), Mock Service Worker for
  API mocks, and Playwright for critical journeys. ESLint and Prettier with `printWidth: 120`.

## SQL (Oracle and PostgreSQL)

- Explicit column lists (no `SELECT *`), explicit `JOIN` syntax, readable CTEs, and descriptive names in
  snake_case. Use bind parameters always.
- Define primary keys, foreign keys, unique and check constraints, and the right data types (timestamps in UTC).
- Check query plans (`EXPLAIN` in PostgreSQL, `EXPLAIN PLAN` in Oracle) and add indexes for real access patterns.
  Use keyset pagination for large result sets.
- Schema changes go through versioned migrations with a rollback plan. Scripts are idempotent where possible.
- No cross-service joins on operational databases. Classify and protect personal data columns, and design deletion
  for erasure requests.
- State the dialect explicitly and call out Oracle versus PostgreSQL differences (sequences and identity columns,
  `MERGE` versus `INSERT ... ON CONFLICT`, empty string and `NULL` handling, `ROWNUM` versus `LIMIT`).
- Test database code against a real engine (Testcontainers), for example with pgTAP or utPLSQL.

## PL/SQL (Oracle)

- Organise code in packages (specification and body). Use `%TYPE` and `%ROWTYPE`.
- Use descriptive names with a consistent, readable prefix scheme, for example `in_customer_identifier` for
  parameters and `local_total_amount` for locals. No cryptic abbreviations.
- Set-based processing first. Use `BULK COLLECT` with `LIMIT` and `FORALL` rather than row-by-row loops.
- Exceptions: named, specific handlers. Never `WHEN OTHERS` without logging and re-raising. Use a central logging
  package. Use autonomous transactions only for logging and audit.
- Use bind variables. For unavoidable dynamic SQL, sanitise identifiers with `DBMS_ASSERT`.
- Choose definer or invoker rights deliberately. Keep new business rules in the application domain layer.
- Test with utPLSQL, written before the code (TDD). Keep all scripts in version control.

## MongoDB

- Model documents around aggregate boundaries and access patterns: embed what is read and changed together,
  reference what is shared or unbounded.
- Define schema validation (JSON Schema) and indexes for real queries. Verify with `explain()`.
- Avoid unbounded arrays and large documents. Use transactions sparingly and only within an aggregate where possible.
- Never build queries from raw user input, to prevent operator injection. Use parameterised drivers and repositories.
- Test against a real MongoDB with Testcontainers.

## Elasticsearch

- Treat Elasticsearch as a read model and search index, never the system of record. Rebuild it from source events
  or data.
- Define explicit mappings (avoid dynamic mapping in production), analyzers, and index templates. Use aliases and
  versioned indices for zero-downtime reindexing.
- Use filters for exact matching and queries for scoring. Use `search_after` for deep pagination.
- Keep personal data and PHI out of indices unless required, and apply field-level security and retention.
- Test with Testcontainers and verify mappings and queries in integration tests.

## Graph databases (Neo4j and FalkorDB)

- Use Cypher (openCypher for FalkorDB). Model nodes and relationships from the domain language, with meaningful
  relationship types and directions.
- Always use query parameters. Create constraints and indexes for lookup properties. Check plans with `EXPLAIN`
  and `PROFILE`.
- Avoid unbounded traversals and cartesian products. Bound path lengths and use `LIMIT`.
- Note feature differences between Neo4j and FalkorDB (supported procedures, indexes, and clauses) before using a
  feature. Test against a real instance with Testcontainers or a container in CI.

## Shell scripts

- Bash with `#!/usr/bin/env bash` and `set -euo pipefail`. Run ShellCheck and format with shfmt.
- Use descriptive names, functions, `local` variables, `trap` for cleanup, and `mktemp` for temporary files.
  Quote all variable expansions.
- Provide `--help` and `--dry-run` for anything that changes state. Return meaningful exit codes, and write logs
  to stderr.
- No secrets in arguments or logs. Make scripts idempotent and safe to re-run.
- Test scripts with bats (test first). If a script needs more than a page or two of logic, move it to Python.

## Docker

- Multi-stage builds, small pinned base images (by version, and by digest for production), and a non-root user.
- One process per container. Add a `.dockerignore`, a `HEALTHCHECK` where appropriate, and use exec-form
  `ENTRYPOINT` so signals reach the process.
- Order layers for cache efficiency. Never bake secrets, credentials, or personal data into images or layers.
- Scan images for vulnerabilities in CI. Lint Dockerfiles (hadolint), and keep them within 120 characters per line.
- Use Docker Compose for local development stacks with the dependencies (databases, brokers) the app needs.

## Kubernetes

- Manage manifests with Helm or Kustomize. Keep environment differences in values or overlays, not copies.
- Set resource requests and limits, liveness, readiness, and startup probes, and graceful shutdown.
- Harden pods: non-root user, read-only root filesystem, dropped capabilities, and a `securityContext`.
  Apply NetworkPolicies, RBAC with least privilege, and namespaces per environment or team.
- Configuration in ConfigMaps. Secrets from an external secrets manager, not committed to Git.
- Use HorizontalPodAutoscaler and PodDisruptionBudgets where relevant, and rolling updates with safe rollback.
- Validate manifests in CI (kubeconform, kube-linter or similar) and test policies before deployment.
- Respect data residency when choosing clusters and regions for personal data and PHI.

## Terraform

- Small, versioned modules with typed and validated variables, and outputs for what other modules need.
- Remote state with locking and encryption. Pin provider and Terraform versions. Separate state per environment.
- Never put secrets in code or variable defaults. Remember that state can contain secrets, so protect it.
- CI runs `terraform fmt -check`, `validate`, `tflint`, a security scan (tfsec, Checkov, or similar), and `plan`.
  Review the plan before `apply`. Apply only from the pipeline, never from a laptop for shared environments.
- Test modules with `terraform test` or Terratest. Use consistent tagging and least-privilege IAM.

## CI/CD

- Pipeline stages, in order: build, lint and format check, unit tests, static analysis and SAST, dependency and
  licence scan, container build and image scan, integration tests, publish an immutable versioned artifact,
  deploy, smoke tests.
- Trunk-based development with short-lived branches, and pull requests that require a green pipeline and review.
- Build once and promote the same artifact through environments. Prefer GitOps for Kubernetes deployments.
- Use canary or blue-green releases with automated rollback. Manage feature flags. Require approvals for production.
- Generate an SBOM and sign artifacts where required. Keep secrets in the CI secrets store with least privilege.
- Keep pipeline definitions in version control, readable, and within 120 characters per line. Fail fast, and cache
  dependencies for speed.
- Enforce architecture and coverage gates (ArchUnit, fitness functions) in the pipeline.

### Jenkins (the CI/CD platform)

- **Pipeline as code:** a declarative `Jenkinsfile` in the repository, run by multibranch pipelines. No jobs
  configured by hand in the UI. Prefer declarative syntax, and keep Groovy script blocks small.
- **Shared libraries** for reusable steps (build, scan, deploy), versioned and pinned by tag. Test them before use
  (TDD) with JenkinsPipelineUnit. Keep `Jenkinsfile` lines within 120 characters.
- **Controller as code:** manage the controller with Jenkins Configuration as Code (JCasC). Run it in a container or
  Kubernetes, back up `JENKINS_HOME`, and pin and regularly update plugins. Keep the plugin list minimal.
- **Agents:** run builds on ephemeral agents (Kubernetes plugin pod templates or Docker agents), never on the
  controller. Use labels for capabilities, and cache Maven and package dependencies.
- **Reliability options:** set `timeout`, `timestamps`, `disableConcurrentBuilds` where needed, and `buildDiscarder`.
  Trigger through webhooks rather than polling. Run independent test stages in `parallel`.
- **Results and gates:** publish JUnit and JaCoCo reports, archive artifacts, and fail the build on quality gate
  failures (for example SonarQube, vulnerability scans). Use `post` blocks for cleanup and notifications.
- **SonarQube:** run analysis on every pull request and main branch build (Maven Sonar scanner or the Jenkins
  plugin), and wait for the quality gate with `waitForQualityGate` so a failed gate fails the build. Gate on new
  code: coverage, duplication, bugs, vulnerabilities, security hotspots, and code smells. Never lower a gate to
  make a build pass, and treat security hotspots as review items. Import JaCoCo coverage and test reports. Set
  the line-length and naming rules in the quality profile to match the 120-character standard.
- **Nexus Repository:** use it as the single source for dependencies and artifacts.
  - Proxy repositories for Maven Central, PyPI, npm, and Docker Hub. Build only through the proxy, not the
    public internet.
  - Hosted repositories for internal Maven, Python, npm, and Docker artifacts, and a group repository as the
    one URL developers and agents use. Separate release and snapshot repositories.
  - Releases are immutable: never overwrite a published version. Publish from the pipeline only, with a
    dedicated service account and least-privilege roles. Use the Maven `distributionManagement` settings and
    the Docker registry in Nexus, and keep credentials in Jenkins credentials.
  - Enable vulnerability and licence checks on components, cleanup policies for old snapshots and images, and
    keep the artifact version and build metadata (commit, build number) traceable.
- **Secrets and access:** use the Jenkins credentials store or an external secrets manager, access them with
  `withCredentials` so values are masked, and never echo them. Apply role-based access control and least privilege.
- **Deployments:** use an `input` step or approval gate for production, promote the same immutable artifact, and
  automate rollback. Lint the `Jenkinsfile` with the declarative linter in the pull request check.

## Code review and definition of done

When reviewing code, report findings in this order: correctness and bugs, security and data protection, design and
boundaries, tests (was it test driven?), clean code and naming, then style. Rank by severity and explain why each
matters.

Done means: tests were written first and pass, the build, linter, formatter, and type checks pass, no line exceeds
120 characters, names are descriptive, no secrets or personal data appear in code or logs, documentation and ADRs
are updated where behaviour or design changed, and a Conventional Commits message describes the change.
