# Branch protection for `main`

`main` had no branch protection, so a red CI run could not stop a merge. That is how the same JDK regression reached
`main` three times (ADR-011, ADR-014, ADR-018). This runbook turns protection on and keeps it reproducible: the
settings live in [`.github/branch-protection.json`](../.github/branch-protection.json) and are applied with one API
call. Branch protection is a repository setting, so a pull request cannot change it; someone with **admin** rights on
the repository has to run the steps below.

## What gets enforced

| Rule | Setting | Why |
|---|---|---|
| Pull request required | on, 0 approvals | Nothing lands on `main` without going through CI. Approvals are 0 while the repository has a single maintainer, who cannot approve their own PR; raise to 1 when a second reviewer joins |
| Required status checks | 13 checks, below | A failing build, test, Helm chart check, image build, image scan, dependency scan or CodeQL run blocks the merge |
| Branch must be up to date (`strict`) | on | Checks run against what `main` will actually become, not a stale base |
| Include administrators (`enforce_admins`) | on | The repository owner cannot bypass the rules either; this is the gap all three regressions went through |
| Stale approvals dismissed on new commits | on | Takes effect once approvals are above 0 |
| Conversations resolved before merge | on | Review comments cannot be silently left open |
| Force pushes and deletion of `main` | off | History on `main` cannot be rewritten or lost |
| Linear history | off | The project merges with merge commits |

Required checks (names exactly as GitHub reports them; `app_id` 15368 is GitHub Actions, so only the workflow can
satisfy a check, not a status posted by anything else):

| Workflow | Check |
|---|---|
| CI | `Backend build and tests` |
| CI | `admin-ui build and tests`, `client-ui build and tests` |
| CI | `Helm chart` (lint, render, schema validation; added with ADR-025, so re-run step 2 once it is on `main`) |
| CI | `Docker images build (...)`, one per image: client-api, admin-api, dispatcher, db-migration, admin-ui, client-ui |
| Security | `Dependency and secret scan` |
| Security | `CodeQL (java-kotlin)`, `CodeQL (javascript-typescript)` |

`Publish signed image (...)` (ADR-027) is deliberately not required: it runs only after a push to `main` or a version
tag, never on a pull request, so requiring it would block every merge.

## Steps

### 1. Confirm you have admin rights and that `main` is green

```bash
gh repo view techeazyhq-cpu/notification-service --json viewerPermission --jq .viewerPermission
```

It must print `ADMIN`.

```bash
gh api "repos/techeazyhq-cpu/notification-service/commits/main/check-runs?per_page=100" --jq '.check_runs[] | "\(.conclusion)\t\(.name)"'
```

Every one of the 13 checks above should be `success`. If a check name differs from the table (a job or matrix entry
was renamed), update `.github/branch-protection.json` first: a required check that never reports blocks every merge.

### 2. Apply the protection

From the repository root:

```bash
gh api -X PUT repos/techeazyhq-cpu/notification-service/branches/main/protection --input .github/branch-protection.json
```

Through the web UI instead: **Settings → Branches → Add branch protection rule**, branch name pattern `main`, then
tick *Require a pull request before merging* (approvals 0, dismiss stale approvals), *Require status checks to pass*
(tick *Require branches to be up to date* and add the 13 checks, choosing **GitHub Actions** as the source for each),
*Require conversation resolution*, *Do not allow bypassing the above settings*; leave force pushes and deletions
unticked. Save.

### 3. Verify

```bash
gh api repos/techeazyhq-cpu/notification-service/branches/main/protection --jq '{checks: (.required_status_checks.checks | length), strict: .required_status_checks.strict, enforce_admins: .enforce_admins.enabled, approvals: .required_pull_request_reviews.required_approving_review_count, force_push: .allow_force_pushes.enabled, deletions: .allow_deletions.enabled, conversations: .required_conversation_resolution.enabled}'
```

Expected: `checks` 13, `strict` true, `enforce_admins` true, `approvals` 0, `force_push` false, `deletions` false,
`conversations` true.

Then prove it bites. From a throwaway local branch based on `main`, make an empty commit and try to push it straight
to `main`:

```bash
git switch -c protection-test origin/main
```

```bash
git commit --allow-empty -m "protection test"
```

```bash
git push origin HEAD:main
```

Expected: `GH006: Protected branch update failed`. Delete the throwaway branch afterwards
(`git switch main` then `git branch -D protection-test`). Finally, open the next PR and check that its merge button
stays disabled until all 13 checks are green.

## Living with it

- **Dependabot PRs** go through the same checks. A bump that breaks the build (the ADR-011/014/018 failure) now stays
  red and unmerged instead of reaching `main`.
- **Renaming a job or matrix entry** in `ci.yml` or `security.yml` changes its check name. Update
  `.github/branch-protection.json` in the same PR and re-run step 2 after it merges, or every later PR waits forever
  on a check that no longer exists.
- **Adding a second maintainer:** set `required_approving_review_count` to 1 and re-run step 2. Consider a
  `CODEOWNERS` file and `require_code_owner_reviews` for `db-migration/`, `.github/` and the auth packages.
- **Emergency fix:** there is no bypass, by design. Raise the fix as a PR like any other; if a check itself is broken,
  fix the workflow in that PR. Temporarily removing protection
  (`gh api -X DELETE repos/techeazyhq-cpu/notification-service/branches/main/protection`) is an admin action to be
  recorded in the PR that needed it, and step 2 re-applied straight after.
- **Drift check:** re-run step 3 whenever the workflows change; a scheduled check can be added later.
