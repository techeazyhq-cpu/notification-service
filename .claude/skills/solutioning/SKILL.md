---
name: solutioning
description: Use when taking a problem from requirements to a recommended architecture - Domain-Driven Design, clean architecture, microservices/micro frontends, event-driven and saga patterns, privacy compliance (GDPR, HIPAA, DPDPA, Middle East), HL7 FHIR for healthcare, with C4 and draw.io diagrams and an ADR. Not for small code changes.
---

# Solutioning

Audience: a senior software architect (18+ years). Skip basics, be concise, lead with trade-offs, and challenge assumptions, including your own recommendation.

## Architectural stance (defaults)

- **Domain-Driven Design, strictly.** Start from the domain, not the technology. Use ubiquitous language, bounded contexts, context maps, aggregates, entities, value objects, domain events, and domain services. Strategic design comes before tactical design.
- **Clean architecture** inside each service: domain at the centre, then application (use cases), then interface adapters, then infrastructure. Dependencies point inward only. Ports and adapters at every boundary.
- **Scale the style to the problem.**
  - Small / simple: a modular monolith with DDD module boundaries is acceptable. Say so, and explain why microservices would be premature.
  - Medium / complex: microservices aligned to bounded contexts, and micro frontends aligned to the same contexts (one team owns UI + API + data for a context).
- **Event-driven where applicable:** for cross-context integration, decoupling, audit trails, and eventual consistency. Use synchronous calls only where a caller genuinely needs an immediate answer. Address idempotency, ordering, outbox pattern, schema versioning, and dead-letter handling.
- **Sagas for distributed transactions:** choose orchestration or choreography and justify it. Define compensating actions, timeouts, and failure states for every saga. Never use distributed two-phase commit.
- **Technology, cloud, and saga style are chosen per problem.** Stay vendor-neutral until the drivers and constraints justify a choice, then compare candidates in the options table and record the reasoning in the ADR. For sagas, weigh orchestration against choreography for each flow.
- **Healthcare problems: HL7 standards are mandatory.** Use HL7 FHIR as the interoperability standard for all external and cross-context health data exchange, and follow the Healthcare section below. Do not invent proprietary health data formats or protocols.
- Deviate from any default only with an explicit reason recorded in the ADR.

## Process

1. **Frame the problem**
   - Problem, goal, success criteria in 3-5 lines. Stakeholders. Out of scope.
   - If key inputs are missing, ask at most 3-5 targeted questions. If the user wants to proceed, list assumptions and carry on.

2. **Domain analysis (DDD, strategic)**
   - Core, supporting, and generic subdomains. Identify the core domain and put the design effort there.
   - Ubiquitous language glossary for key terms.
   - Candidate bounded contexts and a context map (upstream/downstream, partnership, shared kernel, customer-supplier, conformist, anti-corruption layer).
   - Key domain events and commands (event-storming style summary).

3. **Requirements, constraints and compliance**
   - Functional: key capabilities and use cases.
   - Non-functional with numbers where possible: scale, latency, availability, consistency, security, observability, cost.
   - Constraints: existing stack, team skills, timeline, budget, integrations.
   - **Data protection and residency** (see the Compliance section). Identify data classes, jurisdictions, and residency requirements early, since they shape context boundaries and data storage.
   - Express key non-functional requirements as **quality attribute scenarios** (source, stimulus, environment, response, measure), using ISO/IEC 25010 terms. Define automated **fitness functions** for the important ones (dependency rules, layer violations, latency budgets) so they are enforced in CI.
   - Rank the top 3 architectural drivers.

4. **Options and trade-offs**
   - 2-3 genuinely different options, including the simplest viable one (for example a modular monolith versus microservices, or choreography versus orchestration).
   - Compare in a table against the ranked drivers plus: complexity, cost, team fit, operability, compliance fit, and lock-in.
   - Recommend one, state what would change the recommendation, then argue the strongest case for the runner-up.

5. **Tactical design and architecture views**
   - Per bounded context: aggregates and their invariants, domain events, use cases, and the clean architecture layer layout.
   - Integration: event catalogue (name, producer, consumers, payload owner, versioning), API contracts, and saga flows with compensations.
   - Micro frontends: composition approach (module federation, web components, or server-side composition), shared design system, routing, and cross-frontend communication.
   - **Data architecture:** database per service, data ownership per aggregate, persistence choice per context (polyglot only when justified), CQRS and read models, event sourcing only where audit or temporal needs justify it, analytics and reporting path (no cross-service joins on live databases), and data migration approach.
   - **API and contract strategy:** API-first (OpenAPI for synchronous APIs, AsyncAPI for events), versioning and backward-compatibility rules, consumer-driven contract tests, API gateway or BFF per frontend, and a schema registry for event schemas.
   - **Diagrams: produce both formats.**
     - C4 in Mermaid (context, container, component; add dynamic and deployment where useful) embedded in the Markdown.
     - draw.io: `.drawio` files (mxGraph XML) for the same C4 views and for the context map, saga sequences, and deployment. Keep each diagram to one idea. Label protocols and data ownership.

6. **Cross-cutting concerns**
   - **Security architecture:** threat model (STRIDE) for the critical flows, zero-trust principles, identity with OAuth2/OIDC, service-to-service auth (mTLS), secrets and key management, and OWASP ASVS as the verification baseline.
   - **Observability and SRE:** logs, metrics, and traces with correlation IDs propagated across events; SLIs, SLOs and error budgets per key journey; alerting, runbooks, and on-call ownership.
   - **Resilience and disaster recovery:** retries with backoff, circuit breakers, bulkheads, RPO and RTO targets, backup and restore testing, multi-region or failover strategy (respecting data residency), and capacity planning.
   - **Delivery:** CI/CD, infrastructure as code, feature flags, canary or blue-green releases, and rollback.
   - **Testing strategy:** domain unit tests, contract tests, integration and saga tests, end-to-end for critical journeys only, plus performance and security testing.

7. **Risks and failure modes**
   - Table of risk, likelihood, impact, mitigation. Cover dependency failures, event loss or duplication, saga stuck states, data inconsistency, compliance gaps, and operational burden (including the cost of running many services).
   - Open questions and assumptions to validate.

8. **Decision record (ADR)**
   - Title, Status, Context, Decision, Options considered, Consequences (positive and negative), Compliance impact, Follow-ups.

9. **Next steps**
   - Smallest useful spike or walking skeleton to de-risk the top uncertainty, then a phased plan (core domain first).

## Compliance (privacy and data protection)

Design to the strictest applicable regime, and record which apply. Treat this as an architectural input, not a final checklist, and note that it is not legal advice; recommend confirmation by counsel or the DPO.

- **GDPR:** lawful basis, data minimisation, purpose limitation, data subject rights (access, erasure, portability, rectification), privacy by design and default, DPIA for high-risk processing, breach notification (72 hours), cross-border transfer mechanisms, processor agreements.
- **HIPAA:** PHI identification, minimum necessary, access controls and audit logging, encryption at rest and in transit, BAAs with vendors, breach notification, retention.
- **DPDPA (India):** consent and notice, purpose limitation, data principal rights, data fiduciary obligations, children's data, breach reporting, cross-border transfer restrictions as notified.
- **Middle East:** check national and free-zone rules for each country involved, such as the UAE PDPL, DIFC and ADGM regulations, sector rules for health data, Saudi PDPL and its transfer rules, and Qatar's data protection law. Pay particular attention to **data residency and localisation** for health and government data.

Architectural implications to address explicitly:
- Data classification and a data inventory per bounded context. Keep personal and sensitive data in as few contexts as possible.
- Residency-aware deployment (region or tenant partitioning) and where events and backups may travel.
- **Event-driven caveats:** do not put personal data in broadly consumed events; use references or IDs, encrypted fields, or crypto-shredding so erasure is possible in immutable logs.
- Consent management, audit trails, retention and deletion workflows (including propagation of erasure through sagas), pseudonymisation, key management, and access logging.
- Cross-border flows, third-party processors, and breach response.

## Healthcare (HL7 and FHIR, strictly)

Apply to any problem involving clinical, patient, or health-administrative data. Confirm current versions and national profiles before finalising, since they change.

- **FHIR as the published language.** Exchange health data through FHIR resources over the FHIR RESTful API (and FHIR messaging, documents, or Subscriptions where the use case needs them). State the FHIR version (R4 or R5) and justify it against the target ecosystem (R4 is the most widely deployed).
- **Keep the domain model clean.** FHIR resources live at the boundary (adapters and an anti-corruption layer). Do not let FHIR resource structures leak into the core domain model or aggregates; map to and from the ubiquitous language. Document the mapping for each bounded context.
- **Profiles and conformance.** Identify the applicable implementation guides and national or regional profiles (for example US Core, the International Patient Summary, India's ABDM, Saudi NPHIES, and UAE health information exchanges such as Malaffi and NABIDH). Define project profiles and extensions only when no existing profile fits. Publish a CapabilityStatement, and validate resources in CI with the FHIR validator.
- **Terminology.** Use standard code systems: SNOMED CT, LOINC, ICD-10 or ICD-11, RxNorm or the local drug dictionary, UCUM for units. Design a terminology service and a mapping strategy for local codes.
- **Security and consent.** SMART on FHIR with OAuth2/OIDC for authorisation and scopes; FHIR Consent for patient consent; AuditEvent and Provenance for audit and data lineage; align with the HIPAA, GDPR, DPDPA, and Middle East rules above.
- **Identity and matching.** Patient identity strategy (MPI, identifiers, matching, merge and unmerge), and provider and organisation directories.
- **Legacy and other HL7 standards.** Integrate HL7 v2 messages, C-CDA or CDA documents, and DICOM or IHE profiles (XDS, PIX, PDQ) through adapters or an integration engine that translates to FHIR at the boundary. Prefer FHIR for anything new.
- **Bulk and analytics.** FHIR Bulk Data ($export) for population-level and analytics access; do not run analytics against the transactional store.
- **Events and PHI.** Domain events must not carry full FHIR resources or PHI by default. Use resource references and identifiers, and fetch through authorised APIs. Any FHIR Subscription payloads follow the same rule.
- **FHIR server choice** (build, open source such as HAPI, or a managed cloud service) is a per-problem decision recorded in the ADR, with conformance, performance, search, and residency as criteria.
- Diagram the FHIR interfaces in the C4 container view and label each with resource types, profiles, and interaction type.

## Optional checks (include when relevant to the problem)

- **Cost and FinOps:** cost model per option, cost per tenant or transaction, cloud cost guardrails.
- **Team topology:** align teams to bounded contexts (Conway's law); state platform-team boundaries and ownership.
- **Migration:** strangler fig approach for legacy systems, phased roadmap, deprecation policy.
- **Build vs buy vs open source:** evaluation criteria, licensing, exit strategy.
- **Payments:** PCI DSS scope reduction, when card data is involved.
- **Certification and controls:** ISO 27001 or SOC 2 alignment, when the customer or market requires it.
- **Accessibility and localisation:** WCAG, i18n, RTL and Arabic support for Middle East markets.
- **Governance:** link to architecture principles, the ADR log, and a tech radar.

## Output

Default: a Markdown design doc with the sections above and a separate ADR (`adr-NNN-title.md`), plus `.drawio` diagram files alongside embedded Mermaid C4. Keep the design doc to what a reviewer needs; move detail into appendices. Produce Word or slides only if asked.

## Modes

- **Quick spike / sanity check:** steps 1-4 only, in chat, under one page.
- **Full proposal:** all steps, as files.
- **Review of an existing design:** focus on steps 2, 3, 5, 6, 7. Check DDD boundaries, dependency direction, event and saga correctness, and compliance gaps, and report findings ranked by severity.

## Principles

- Model the domain first; technology follows.
- Prefer the simplest design that meets the ranked drivers. Justify each added moving part, especially each additional service.
- Make trade-offs explicit; never present a choice as free.
- Separate facts, assumptions, and opinions. Do not invent numbers; mark estimates as estimates.
- Design for operability and for compliance from the start.
