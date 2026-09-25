# ADR-014: The JDK 21 build image regressed again, past the ADR-011 guard

- **Status:** Accepted
- **Date:** 2026-09-25

## Context

ADR-011 pinned the root `Dockerfile`'s build stage to `maven:3.9-eclipse-temurin-21` after JDK 24 silently broke every
Lombok-generated accessor (`@Getter`/`@Setter`/`@NoArgsConstructor` produced no methods, so the build failed with
"cannot find symbol" everywhere Lombok was used), and added a Dependabot `ignore` rule for `semver-major` updates on
every ecosystem, including `docker`, to stop it recurring unreviewed. ADR-011 flagged one specific doubt about that
guard: *"Dependabot's Docker version comparisons for tags like `24-jre` aren't strict semver, so the `ignore` rule's
effectiveness there depends on Dependabot's own tag-parsing heuristics — worth spot-checking after a few cycles."*

That doubt was correct. A Dependabot PR bumped the build image straight to `maven:3-eclipse-temurin-26` — the exact
same failure mode as JDK 24, confirmed by rebuilding `admin-api`'s image and hitting the identical
"cannot find symbol: method getXxx()" errors against Lombok-generated accessors — and merged, because the `docker`
ecosystem's `semver-major` ignore rule did not treat `-24` → `-26` in that tag as a major-version change. The image
job in `.github/workflows/ci.yml` would have caught this on that PR (it builds every image with `docker/build-push-action`),
but CI is not yet a required status check on `main` (an open follow-up already listed in ADR-011), so a red run did
not block the merge.

## Decision

1. **Re-pin** the build stage to `maven:3.9-eclipse-temurin-21`, matching ADR-011.
2. **Add a comment directly above the `FROM` line** explaining why, and naming this ADR and ADR-011, so a future
   Dependabot PR touching this line gets a human who reads the comment before approving, not just a green diff.
   This is deliberately a comment and not a stronger Dependabot rule: the underlying problem is that Dependabot's
   version comparison for a Docker tag like `3-eclipse-temurin-26` cannot be trusted to recognize a JDK bump as
   "major" at all, so tightening the `ignore` block further would be tuning a heuristic that has already been shown
   to fail silently, which is a false sense of security rather than a fix.
3. **Not fixed here, but the real fix:** make **CI** a required status check on `main`, per ADR-011's own follow-up.
   This regression reaching `main` at all is that gap, not a new one.

## Options considered

- **Tighten the Dependabot `ignore` rule for the `docker` ecosystem specifically for this image.** Rejected for now:
  Dependabot's Docker tag parsing is the thing that already got this wrong once, so a rule built on the same
  parsing is not a dependable guard. Revisit if Dependabot's own handling of non-semver image tags improves.
- **Move to a JDK-version-pinned base image tag that can't drift (a digest pin).** Would prevent *any* update,
  including safe patch releases of Temurin 21 itself, trading a real but rare failure mode for routinely stale patch
  versions. The comment plus (eventually) required CI is a better trade for an actively maintained project.
- **Upgrade Lombok instead of pinning the JDK.** Worth investigating independently, but out of scope for a same-day
  regression fix, and ADR-011 already decided the project targets Java 21 deliberately, not as a Lombok workaround.

## Consequences

Positive: `docker build` for every module works again; the specific failure mode (silent Lombok breakage on a newer
JDK) is now documented in two places (ADR-011 and this ADR) plus inline at the exact line most likely to reintroduce
it.

Negative / accepted: the underlying gap — CI not being a required check — is still open; nothing in this change
stops a *different* kind of bad Dependabot PR from merging the same way. That is ADR-011's follow-up, not a new one,
and is called out again here because it is now the second time the absence of that guard let a red build reach
`main`.

## Follow-ups

Make **CI** and **Security** required status checks on `main` (repeated from ADR-011, now with two incidents behind
it, not zero); consider a scheduled job that rebuilds all six Docker images against `main` independent of Dependabot,
so a drift like this is caught even between Dependabot runs.
