# ADR-011: Roll back the Spring Boot 4 and JDK 24 Dependabot bumps

- **Status:** Accepted
- **Date:** 2026-09-25

## Context

Dependabot's major-version updates were being merged without a build check in front of them (the CI workflow's own first run coincided with the first such merge, so there was never a green baseline to compare against). Two of the merged bumps broke `main`:

1. **`org.springframework.boot:spring-boot-starter-parent` 3.5.16 → 4.1.1** (PR #16, part of the Dependabot `spring` group). Spring Boot 4 moved and removed APIs the code uses directly (`org.springframework.boot.autoconfigure.domain.EntityScan` no longer exists there), so every module using `@EntityScan` in `CoreConfig` failed to compile, and everything downstream of `notification-core` failed with it.
2. **`maven:3.9-eclipse-temurin-21` → `maven:3-eclipse-temurin-24`, and the runtime `eclipse-temurin:21-jre` → `24-jre`** (PRs #24, #25, unrelated to #16 but merged around the same time). Building the project's `@Getter`/`@Setter`/`@NoArgsConstructor` (Lombok) classes under JDK 24 silently produced no generated methods (a Guice-based annotation-processing warning about `HiddenClassDefiner`/`sun.misc.Unsafe` appeared first), so every entity's accessors disappeared and compilation failed with "cannot find symbol" across the codebase, in Docker builds specifically (the local JDK used for `mvn test` was 21, so this was invisible outside Docker).

A third, unrelated break surfaced while verifying the fix: `admin-ui/package-lock.json` had drifted out of sync with `admin-ui/package.json` (the lockfile resolved `typescript@7.0.2` while the manifest still asked for `~5.9.0`), so `npm ci` refused to install. This was a merge artifact of the TypeScript-major Dependabot PR (#23), not caused by the Spring Boot or JDK changes, and is fixed by resyncing the lockfile via `npm install` rather than by reverting anything.

Springdoc's own major version (2.x → 3.x, PR #42) tracks Spring Boot's major version, so it has to move with Spring Boot, not independently.

## Decision

1. **Revert to the pinned, previously-working versions:**
   - `spring-boot-starter-parent`: back to `3.5.16`.
   - `springdoc.version`: back to `2.8.17` (the 3.x line targets Spring Boot 4).
   - The build image in `Dockerfile`: back to `maven:3.9-eclipse-temurin-21`.
   - The runtime image in `Dockerfile`: back to `eclipse-temurin:21-jre`.
   - `admin-ui`'s lockfile resynced to its manifest (`npm install`), not a version change.
2. **Keep** the other bumps that were already merged and proved to work: Pulsar client 4.2.4, Resilience4j 2.4.0, ArchUnit 1.5.0, Testcontainers/JaCoCo/commons-csv patch bumps, Node 25 base images, Vite 8, React Router and the rest. These are independent of the Spring Boot version and the full test suite and a live Docker run pass with them in place.
3. **Stop future major-version bumps from merging unreviewed.** `.github/dependabot.yml` now sets `ignore: [{dependency-name: "*", update-types: ["version-update:semver-major"]}]` on every ecosystem (Maven, both npm projects, all three Docker directories, GitHub Actions). Dependabot will still open PRs for minor and patch updates; a major version bump (Spring Boot 5 someday, another Pulsar or springdoc major, etc.) has to be a deliberate, reviewed PR, the way this document is.

## Options considered

- **Do the Spring Boot 4 migration properly instead of reverting.** The right long-term move, but it's a real migration (Spring Framework 7, Jakarta EE changes, dropped APIs beyond `EntityScan`) that deserves its own reviewed PR with its own testing, not something to land as a side effect of an unattended dependency bump. Tracked as a follow-up.
- **Only fix `EntityScan` and leave Spring Boot 4.** Doesn't address the rest of the surface Spring Boot 4 changes (Spring Security, actuator, etc. were not audited), and reverting was faster to get back to a known-good state.
- **Leave JDK 24 and pin an older Lombok that supports it.** Possible, but the project deliberately targets Java 21 (`<java.version>21</java.version>`); there's no reason to build on 24 at all right now.

## Consequences

Positive: `main` compiles and the full test suite (271 tests across 6 modules) passes again; the Docker stack builds and runs end-to-end (verified: all three backend services healthy, both UIs serving, a live send returns 202); future major-version Dependabot PRs need a human to lift the `ignore` rule or merge them explicitly, so this can't repeat silently.

Negative / accepted: Spring Boot 4, springdoc 3.x and a JDK 24 build stay on the table as a genuine upgrade to plan for, not merged; Dependabot's Docker version comparisons for tags like `24-jre` aren't strict semver, so the `ignore` rule's effectiveness there depends on Dependabot's own tag-parsing heuristics — worth spot-checking after a few cycles.

## Follow-ups

Plan the Spring Boot 4 / JDK 24 migration as its own reviewed piece of work; audit the rest of the already-merged majors (TypeScript 7, Vite 8, Node 25) for anything else that only breaks under conditions this session didn't exercise; make **CI** and **Security** required status checks on `main` so a red run blocks the merge button, not just this ignore rule.
