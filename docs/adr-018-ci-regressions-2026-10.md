# ADR-018: Third JDK regression, two CVEs, and a Dockerfile misconfiguration reaching `main`

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

A routine review of `main` found four unrelated problems, all live at the same time:

1. **The JDK regression happened a third time.** Dependabot PR #58 bumped the root `Dockerfile`'s build stage from
   `maven:3.9-eclipse-temurin-21` to `maven:3-eclipse-temurin-24` and merged. This is the exact failure ADR-011
   fixed once (JDK 24 silently breaks Lombok's code generation) and ADR-014 fixed again for JDK 26: the Docker
   image build job in CI fails every module with "cannot find symbol" against Lombok-generated accessors. ADR-014's
   own comment on the `FROM` line, naming both prior ADRs, did not stop it — a Dependabot PR has no human reading
   Dockerfile comments before Dependabot itself decides whether the bump is "major," and here it still did not
   count a `-21` → `-24` tag change as one.
2. **A new dependency CVE.** `org.postgresql:postgresql` 42.7.11 (the version Spring Boot 3.5.16 manages) has
   CVE-2026-54291 (HIGH), fixed in 42.7.12.
3. **Two more CVEs, found only once the first two were fixed and the built images could actually be scanned.**
   `org.apache.tomcat.embed:tomcat-embed-core` 10.1.55 (Spring Boot 3.5.16's managed version) has three CRITICAL
   CVEs (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525), fixed in 10.1.58 upstream (a version that turned out not
   to actually exist on Maven Central; 10.1.60 is the latest 10.1.x release and includes the fix). Separately,
   `pulsar-client` 4.2.4 **shades** (repackages under `org.apache.pulsar.shade.*`) its own copies of
   `io.netty:netty-handler` 4.1.135.Final (CVE-2026-75595, CRITICAL) and `org.bouncycastle:bcprov-jdk18on` 1.84
   (CVE-2026-8763, CRITICAL) — neither appears in `mvn dependency:tree` at all, which is why the dependency-scan
   workflow (a filesystem/POM scan) passed while the image-scan workflow (which inspects the actual jar contents)
   failed on exactly the three modules that depend on `notification-core`'s Pulsar client.
4. **A Dockerfile misconfiguration.** `admin-ui/Dockerfile` and `client-ui/Dockerfile` build on
   `nginxinc/nginx-unprivileged`, which already runs as a non-root `nginx` user by its own default — but neither
   Dockerfile said so with an explicit `USER` instruction, so Trivy's static analysis (which only reads the
   Dockerfile's own instructions, not the base image's internal default) correctly cannot verify it and flags
   `DS-0002` (HIGH).

All four failed the **CI** and **Security** workflows on `main`, and all had been failing silently because neither
workflow is a required status check yet — the same gap ADR-011 and ADR-014 both named as the real missing piece,
now responsible for a third incident.

## Decision

1. **Re-pinned** the build stage to `maven:3.9-eclipse-temurin-21` (ADR-011's original decision, unchanged).
2. **Dependabot now ignores the `maven` Docker dependency outright** (`.github/dependabot.yml`), not just its
   major-version updates. The existing `semver-major` ignore rule was never the actual gap — Dependabot's own
   tag-parsing is, and a rule built on that same parsing cannot be trusted to close it. A JDK bump for this image is
   now **always** a deliberate, human-authored PR; Dependabot will not propose one at all.
3. **Pinned `postgresql.version` to `42.7.12`** and **`tomcat.version` to `10.1.60`** as root `pom.xml` properties,
   overriding what Spring Boot 3.5.16 manages, until Spring Boot itself picks up the patched lines.
4. **Accepted, documented and time-boxed the two shaded-dependency CVEs** in a new `.trivyignore` (referenced
   explicitly from both `ci.yml`'s image scan and `security.yml`'s filesystem scan), rather than leaving the build
   red for a problem no `pom.xml` change can reach: `pulsar-client` 4.2.4 is the latest stable release (5.0.0 is
   pre-release milestones only), so there is no newer version to move to yet. Each entry names the specific CVE,
   explains why this project's actual usage does not exercise the vulnerable code path (Netty's CVE needs
   SNI-routing proxy behaviour we don't use; BouncyCastle's CVE needs a name-constrained CA, and the private CA
   this project generates for Pulsar TLS, ADR-017, sets no constraints), and carries a 2026-12-31 expiry so the
   ignore cannot silently become permanent — Trivy fails the check again once an entry expires, forcing a conscious
   decision (extend it with a fresh reason, or upgrade `pulsar-client` by then).
5. **Added `USER nginx`** to both UI Dockerfiles, right after the files it needs to read are copied in. Verified by
   actually running the built image and checking the process's uid, not just the Dockerfile lint passing.

## Options considered

- **Keep relying on Dependabot's major-version ignore rule and a Dockerfile comment.** Already tried twice (ADR-011,
  ADR-014); this is the proof it does not work for this specific tag format. Not repeating it a third time.
- **Pin the build image by digest instead of tag.** Would stop Dependabot from proposing *any* update, including
  safe Temurin 21 patch releases, which is more than this problem needs; the targeted `ignore` entry gets the same
  outcome (no automated JDK bump) without also blocking patch updates within the pinned major version.
- **Suppress DS-0002 instead of fixing it.** Rejected: the fix is one line, costs nothing, and makes the already-true
  security property (non-root) verifiable by someone who has not read the base image's own Dockerfile.
- **Downgrade `pulsar-client`, or pin an older release, to dodge the shaded CVEs.** Checked first: no release of
  `pulsar-client`, at any version on Maven Central, ships a shade built from a patched Netty or BouncyCastle yet —
  these are very recent CVEs (both published after Pulsar 4.2.4's own build date). Downgrading would trade a known,
  assessed, time-boxed risk for an *older* set of unassessed ones.
- **Lower the image scan's severity threshold or drop it from the required jobs instead of using `.trivyignore`.**
  Rejected: that would stop catching every other CRITICAL in future image builds, not just these two specific,
  already-assessed ones.

## Consequences

Positive: `main` builds again; the CVE is closed without waiting for an upstream Spring Boot release; the
non-root property of both UI images is now explicit and machine-checkable, not just true by inheritance; the JDK
pin can no longer be silently reopened by an automated dependency bump.

Negative / accepted: Dependabot will never again propose a JDK bump for this image, including a legitimate future
one (to JDK 25 LTS, say) — a human has to notice that opportunity and act on it deliberately, which is the explicit
trade-off, not an oversight. The underlying gap (CI and Security are not required status checks) is still open;
this is the third ADR to say so.

## Follow-ups

Make **CI** and **Security** required status checks on `main` — repeated a third time, now with three incidents
behind it instead of one or two; the fix belongs in the repository's branch protection settings, which is outside
what a pull request can change, so it needs a deliberate action by someone with admin access to the repository.
Watch for a `pulsar-client` release past 4.2.4 and upgrade to it, which should resolve both `.trivyignore` entries
without needing to extend their expiry; track this independently of this ADR since it depends on an upstream
release this project does not control (see issue #64).
