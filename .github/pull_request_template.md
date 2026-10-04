<!-- Title: Conventional Commits form, e.g. "fix: refuse a bulk upload larger than the documented cap". See CONTRIBUTING.md. -->

## Summary

<!-- What changes and why, in a few sentences. Link the issue it closes. -->

Closes #

## Changes

<!-- Notable changes grouped by area. Call out migrations, new configuration properties, changed defaults and new dependencies. -->

-

## Test plan

<!-- What you ran and what you checked by hand. Add screenshots for UI changes. -->

- [ ] `mvn verify`
- [ ] `npm run build` in each changed UI (and `npm test` in client-ui)
- [ ] Terraform, Helm or Docker checks, if those files changed
- [ ] Checked by hand:

## Risk and rollback

<!-- What could break, who is affected, and how to undo it. Required for migrations, authentication, billing, retention and erasure. -->

## Checklist

- [ ] One focused change, rebased on or merged with the latest `main`
- [ ] Tests added or updated; a fixed bug has a test that fails without the fix
- [ ] No new Sonar or Trivy finding, and every suppression states its reason
- [ ] Database changes are a new Liquibase changeset; no merged changeset was edited
- [ ] No secrets, real recipient addresses or personal data in code, tests or logs
- [ ] New source files carry the license header
- [ ] Docs, error codes, Postman collection and an ADR updated where behaviour or a decision changed
