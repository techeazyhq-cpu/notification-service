# ADR-049: Dependabot opens security updates only

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR-011, ADR-027

## Context

`.github/dependabot.yml` asked Dependabot for weekly version updates in seven places: Maven, both npm projects, three
Docker directories and GitHub Actions. That came to about seven pull requests a week, most of them patch bumps and
base-image digest changes. Each one needs a CI run and, because branch protection requires an up-to-date branch,
another run after every merge ahead of it. The time this took was out of proportion to what the bumps were worth.

Dependabot alerts and security updates were switched off in the repository settings, so the version updates were also
the only way Dependabot told anyone about a vulnerable dependency.

## Decision

1. **Remove `.github/dependabot.yml`.** Dependabot opens no more routine version-update pull requests.
2. **Turn on Dependabot alerts and Dependabot security updates** in the repository settings. A dependency with a known
   advisory still gets an alert and a pull request that bumps it to the first fixed version.
3. **Security pull requests go through the same required checks** as any other pull request (see
   `docs/branch-protection.md`). The semver-major `ignore` rule from ADR-011 no longer exists. When a security fix
   needs a major version, its pull request stays red if it breaks the build, and someone upgrades by hand, as ADR-011
   asks for any major upgrade.

## Consequences

- Far fewer pull requests. Dependencies change only when there is an advisory or someone upgrades on purpose.
- Patch releases without an advisory (bug fixes, performance fixes) are no longer picked up automatically.
- Security updates do not cover Docker base images. The digests pinned in the Dockerfiles and the action SHAs in the
  workflows (ADR-027, PR #114) now stay where they are until someone bumps them. Trivy still scans every image in CI
  and in the weekly security run, so a vulnerable base image fails a build, but nothing opens the fix for it.
- Bumping base-image digests and action SHAs is now a manual task, worth doing before each release.

## Alternatives considered

- **Keep version updates but run them monthly and group each ecosystem into one pull request.** About four pull
  requests a month. Rejected for now, because the owner wants routine bumps to stop. It is the obvious way back if
  digests and patch releases fall too far behind.
- **Remove Dependabot entirely, with no alerts either.** Rejected: nothing would then report a known vulnerability in a
  dependency until a Trivy scan fails a build.
