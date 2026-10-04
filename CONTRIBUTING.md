# Contributing to notification-service

Thank you for helping improve notification-service. This guide explains how to report problems, set up a development
environment, and get a change merged. Read it once before your first pull request; it is short on purpose.

## Contents

- [Ground rules](#ground-rules)
- [Reporting a security vulnerability](#reporting-a-security-vulnerability)
- [Reporting a bug or asking for a feature](#reporting-a-bug-or-asking-for-a-feature)
- [Setting up a development environment](#setting-up-a-development-environment)
- [Making a change](#making-a-change)
- [Coding standards](#coding-standards)
- [Pull request guidelines](#pull-request-guidelines)
- [Review and merge](#review-and-merge)
- [License](#license)

## Ground rules

- **Be respectful.** Assume good intent, keep feedback about the code, and help newcomers.
- **Nothing lands on `main` directly.** Every change, however small, goes through a branch and a pull request, and
  every required check must be green before it merges ([branch protection](docs/branch-protection.md)).
- **One change per pull request.** A bug fix, a feature or a refactoring, not all three.
- **Discuss large changes first.** Open an issue before you start on anything that changes the architecture, the
  public API, the database schema or the deployment model, so nobody spends a week on a direction that will not merge.

## Reporting a security vulnerability

**Do not open a public issue for a security problem.** Report it privately by email to
[vasantha.kumar@hotmail.com](mailto:vasantha.kumar@hotmail.com). Include:

- the affected version or commit;
- the steps to reproduce, or a proof of concept;
- what an attacker gains.

You will get an acknowledgement within a few working days. Please give a fix the chance to ship before you disclose the
problem publicly.

## Reporting a bug or asking for a feature

Search the existing issues first; if one already covers it, add your details there.

A useful **bug report** says:

- what you did, what you expected and what happened;
- the version or commit, and how you run it (Docker Compose, Kubernetes, from the IDE);
- relevant logs, with API keys, passwords and recipient addresses removed;
- the error code (`NS-xxxx`) and trace id from the error response, if you have them. They are explained in
  [docs/error-codes.md](docs/error-codes.md).

A useful **feature request** describes the problem you are trying to solve before the solution you have in mind.

## Setting up a development environment

You need:

- **JDK 21**;
- **Maven 3.9** or newer;
- **Node 22** (20 also works);
- **Docker** with Docker Compose. Docker is used for the local stack and for the PostgreSQL integration tests
  (Testcontainers); those tests are skipped when Docker is absent.

```bash
git clone https://github.com/techeazyhq-cpu/notification-service.git
cd notification-service
docker compose --profile app up -d --build   # datastores, the three services and both UIs
node scripts/seed.mjs                        # providers, templates and a demo client (prints its API key once)
```

The [README](README.md) lists the local URLs and sign-ins. It also explains how to run a service from your IDE against
the containerised datastores, and how to run a UI with hot reload.

## Making a change

1. **Fork** the repository (or, if you are a maintainer, branch in it) and start from an up-to-date `main`:

   ```bash
   git fetch origin
   git checkout -b fix/short-description origin/main
   ```

   Name the branch after the kind of change: `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, `perf/`, `build/`,
   `ci/` or `chore/`, followed by a few words in kebab case.

2. **Write a failing test first**, then make it pass, then tidy up. Every bug fix comes with a test that fails without
   it.

3. **Run the same checks CI runs** before you push:

   ```bash
   mvn verify                                   # Java build, unit and integration tests, coverage
   (cd admin-ui && npm ci && npm run build)     # type-check and build the admin console
   (cd client-ui && npm ci && npm run build && npm test)
   ```

   If you touched Terraform, the Helm chart or a Dockerfile, also run what the matching CI job runs
   (`.github/workflows/ci.yml`):

   - Terraform: `terraform fmt -check -recursive deploy/terraform`, then `terraform test` in the module you changed;
   - the Helm chart: `helm lint`;
   - a Dockerfile: `docker build`.

4. **Keep static analysis at zero.** Sonar findings are kept at zero (see *Static analysis* in the [README](README.md)),
   and so are Trivy misconfiguration findings. Fix what a scan reports. Suppress a finding only when it does not apply,
   and then state why next to the suppression.

5. **Commit** in small, logical steps using [Conventional Commits](https://www.conventionalcommits.org/):

   ```text
   fix: keep the navigation and Sign out in view while the page scrolls
   feat: recipient activity report for answering complaints to a data protection authority
   ```

   - **Type:** one of `feat`, `fix`, `docs`, `refactor`, `test`, `perf`, `build`, `ci` or `chore`.
   - **Summary:** imperative and lower case, with no full stop, describing the effect rather than the code.
   - **Body:** add one when the reason is not obvious. Explain *why*; the diff already shows *what*.

6. **Push and open a pull request** against `main`, following the guidelines below.

## Coding standards

**Code**

- **Clean code.** Use small methods, intention-revealing names and no dead code. Prefer self-documenting code to
  comments.
- **No inline comments.** Explain intent in Javadoc (or TSDoc) on the type or method. An inline comment is acceptable
  only when it suppresses a tool finding and states why (for example `NOSONAR` or `trivy:ignore`).
- **License header.** Every new source file starts with the Apache 2.0 header and an author line. Copy it from any
  existing file of the same type.
- **Architecture.** Respect the module boundaries in the [design](docs/design.md): `notification-core` holds the
  domain, application services and ports, and the services only adapt it to HTTP, Pulsar or the database.

**Data and secrets**

- **Database changes** go in a new Liquibase changeset in `db-migration/src/main/resources/db/changelog/`.
  - Never edit a changeset that has already been merged.
  - Follow the safe-migration rules in [ADR-003](docs/adr-003-liquibase-migration-job.md): backward-compatible steps,
    and indexes built concurrently.
- **No secrets in the repository.** Configuration comes from environment variables. The services refuse to start
  with a default secret outside the local profile ([ADR-013](docs/adr-013-fail-fast-on-default-secrets.md)); keep it
  that way.
- **Personal data** (recipient addresses, message content) never goes into logs, metrics, traces or Pulsar messages.

**Decisions and documentation**

- **Significant decisions get an ADR.** Add `docs/adr-NNN-short-title.md` with the next free number, following the
  existing ones (context, decision, consequences). Amend an existing ADR instead of contradicting it silently.
- **Docs follow behaviour.** When you change what a user or operator sees, update in the same pull request whichever
  of these the change affects:
  - the [README](README.md);
  - the [client guide](docs/client-guide.md);
  - the [tenant onboarding runbook](docs/tenant-onboarding.md);
  - the [error code dictionary](docs/error-codes.md);
  - the [Postman collection](postman).

## Pull request guidelines

A pull request is easy to review, and therefore quick to merge, when it is:

- **Focused.** One purpose. If you find an unrelated problem on the way, open an issue or a separate pull request.
- **Small.** Aim for a diff a reviewer can read in one sitting. Split large work into a series of pull requests that
  each leave `main` working.
- **Up to date.** Rebase on, or merge, the latest `main` before asking for review, and resolve conflicts yourself.
- **Green.** Every required check passes. If a check fails for a reason unrelated to your change, say so in the
  pull request rather than re-running it until it passes.

### Title

Use the same Conventional Commits form as a commit summary, for example
`fix: refuse a bulk upload larger than the documented cap`. The title becomes the merge commit's summary.

### Description

The [pull request template](.github/pull_request_template.md) is filled in automatically. Complete every section:

- **Summary:** what changes and why, in a few sentences a reviewer can read before the diff. Link the issue it closes
  (`Closes #123`).
- **Changes:** the notable changes, grouped by area, including anything a reviewer might miss: a migration, a new
  configuration property, a changed default, a new dependency.
- **Test plan:** what you ran and what you checked by hand, as a checklist. Show evidence for behaviour the tests
  cannot see, such as a screenshot of a UI change.
- **Risk and rollback:** what could break, who is affected, and how to undo it. This is required for migrations and
  for changes to authentication, billing, retention or erasure.

### Required checks

These must all pass before a pull request can merge. They are defined in `.github/workflows` and listed in
[docs/branch-protection.md](docs/branch-protection.md).

| Check | What it verifies |
|---|---|
| Backend build and tests | `mvn verify` (unit tests, PostgreSQL integration tests, coverage), the backup and restore drill self-test, and the Prometheus SLO and alert rule tests |
| admin-ui build and tests | Type-check, build, `npm audit` of runtime dependencies |
| client-ui build and tests | Type-check, build, unit tests, `npm audit` of runtime dependencies |
| Helm chart | Lint, render with every values file and validate against the Kubernetes schemas, including each cloud's manifests |
| Terraform | Format check, module tests, validation of each production environment, and tflint, for AWS, Azure and GCP |
| Docker images build (×6) | Each image builds and has no critical vulnerability (Trivy) |
| Dependency and secret scan | No high or critical vulnerable dependency, committed secret or misconfiguration (Trivy) |
| CodeQL (Java, TypeScript) | No new code-scanning alert |

### Things that block a merge

- A failing or skipped required check.
- Missing tests for new behaviour or for a fixed bug.
- A new Sonar or Trivy finding, or a suppression without a stated reason.
- An edit to an already-merged migration.
- A secret, a real recipient address or personal data anywhere in the diff, test data included.
- An undocumented change to the public API, an error code or a configuration property.

## Review and merge

- A maintainer reviews every pull request. Expect questions; they are about the code, not about you.
- Answer every review comment: either with a change, or with a reply explaining why not. Push follow-up commits rather
  than force-pushing during review, so the reviewer can see what changed since their last pass.
- A maintainer merges once the checks are green and the conversations are resolved. The branch is then deleted.

## License

notification-service is licensed under the [Apache License, Version 2.0](LICENSE). By submitting a contribution you
agree that it is licensed under the same terms, as set out in section 5 of the license, and that you have the right to
submit it.
