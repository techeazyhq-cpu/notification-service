# notification-service

Generic multi-channel notification platform: **Email, SMS, WhatsApp, App Push**, built on **Apache Pulsar**.

- **Client API** (`:8080`): REST for single and bulk sends (JSON body or CSV upload) and request/message status queries. Swagger UI at `/swagger-ui.html`.
- **Client UI** (`:5174`): sign in with a client API key to follow your requests: summary, per-request progress with live updates, per-recipient outcomes, search, CSV export of failed recipients, and your own message templates (create, edit, preview).
- **Admin API** (`:8081`) + **Admin UI** (`:5173`): clients and API keys, templates, providers, rate limits, dashboard, failed-message retry.
- **Dispatcher** (`:8082`): Pulsar consumers that rate-limit, render, send, retry and fail over between providers.
- Local catchers: **Mailpit** (email, UI `:8025`) and a small **catcher** for SMS/WhatsApp/Push (UI `:9000`).

Design: [docs/design.md](docs/design.md) · Decisions: [ADR-001](docs/adr-001-modular-monolith-pulsar-outbox.md), [ADR-002](docs/adr-002-client-owned-templates-content-snapshot.md), [ADR-003](docs/adr-003-liquibase-migration-job.md)

## Run it

Requires Docker, and Node 20+ for the seed script and UI.

```bash
docker compose --profile app up -d --build     # postgres, redis, pulsar, mailpit, catcher, 3 services, both UIs
node scripts/seed.mjs                          # providers, templates, demo client (prints its API key once)
```

| What | URL | Sign-in |
|---|---|---|
| Admin UI | http://localhost:5173 | `admin` / `admin` |
| Client tracker | http://localhost:5174 | the demo client's API key from the seed output |
| Client API (Swagger) | http://localhost:8080/swagger-ui.html | `X-API-Key` header |
| Mailpit (captured email) | http://localhost:8025 | none |
| SMS / WhatsApp / Push catcher | http://localhost:9000 | none |

Stop with `docker compose --profile app down` (add `-v` to also wipe the database and Pulsar data). For UI development
with hot reload run `npm run dev` in `admin-ui` or `client-ui` instead of the containers (stop the matching container
first, the ports are the same).

Infra only (run services from your IDE): `docker compose up -d`, then `mvn -q -DskipTests package` and
`java -jar client-api/target/client-api-*.jar` (likewise `admin-api`, `dispatcher`). If the dispatcher runs on your host,
seed with `SMTP_HOST=localhost CATCHER_URL=http://localhost:9000 node scripts/seed.mjs`.

## Try it

```bash
KEY=ntf_...   # from the seed output

# single email
curl -X POST localhost:8080/v1/notifications -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"channel":"EMAIL","recipient":"ann@example.com","templateName":"welcome-email","variables":{"name":"Ann"}}'

# bulk SMS from JSON
curl -X POST localhost:8080/v1/notifications/bulk -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"channel":"SMS","templateName":"otp-sms","recipients":[{"recipient":"+14155550101","variables":{"code":"1111"}},{"recipient":"+14155550102","variables":{"code":"2222"}}]}'

# bulk from CSV (header: recipient,name,orderId)
curl -X POST localhost:8080/v1/notifications/bulk/upload -H "X-API-Key: $KEY" \
  -F channel=WHATSAPP -F templateName=order-whatsapp -F file=@recipients.csv

# your own template, then send with it (your template wins over a shared one of the same name)
curl -X POST localhost:8080/v1/templates -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"name":"promo-sms","channel":"SMS","body":"Hi {{name}}, use code {{code}}"}'
curl -X POST localhost:8080/v1/notifications -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"channel":"SMS","recipient":"+14155550123","templateName":"promo-sms","variables":{"name":"Ann","code":"X1"}}'

# status
curl localhost:8080/v1/notifications/<requestId> -H "X-API-Key: $KEY"
curl "localhost:8080/v1/notifications/<requestId>/messages?status=FAILED" -H "X-API-Key: $KEY"
```

Failure drills: `curl -X POST "localhost:9000/admin/fail?status=503&count=3"` makes the next 3 SMS/WhatsApp/Push sends fail
(watch them go `RETRYING` then `SENT`); a recipient ending in `0400` gets a permanent `400` and ends `FAILED`.

Circuit breaker drill (SMS): `curl -X POST "localhost:9000/admin/fail?status=503&count=100000"`, send ~15 SMS, then
`curl localhost:8082/actuator/providerhealth` shows `catcher-sms` `OPEN`; messages stay queued (not `FAILED`). Clear it with
`curl -X POST "localhost:9000/admin/fail?count=0"`: after ~30 s the breaker half-opens, closes, and the backlog is delivered.

## Layout

| Path | What |
|---|---|
| `notification-core` | Domain entities, repositories, Pulsar publisher, Redis rate limiter, outbox sweeper |
| `db-migration` | One-shot Liquibase job that owns the schema: `update`, `update-sql`, `status`, `validate`, `history`, rollback |
| `billing` | Plans, postpaid invoices, prepaid credit; clean-architecture module with ArchUnit rules (ADR-004) |
| `client-api` / `admin-api` / `dispatcher` | The three Spring Boot deployables |
| `admin-ui` | React + Vite admin SPA |
| `client-ui` | React + Vite SPA for API clients: track requests, manage their own templates |
| `tools/catcher` | SMS/WhatsApp/Push gateway stand-in |
| `docs` | Design, ADRs, draw.io container diagram |

## Admin sign-in, password and two-factor

The admin UI signs in with a user name and password (first start: `admin` / `admin`, seeded from `ADMIN_USERNAME` / `ADMIN_PASSWORD` only when there is no administrator yet). Open **My account** to change the password (12+ characters) and to turn on two-factor authentication: scan the QR code with an authenticator app, enter the 6-digit code, and keep the 10 recovery codes. Sessions end after 30 idle minutes; 5 wrong attempts lock the account for 15 minutes. Set `ADMIN_TWO_FACTOR_KEY` to your own secret (it encrypts the stored two-factor keys). API scripts sign in with `POST /api/admin/auth/login` and send the returned token as `Authorization: Bearer ...`; `scripts/seed.mjs` does this and reads `ADMIN_OTP` when two-factor is on. See ADR-005.

## API playground

The client UI has an **API playground** for trying the Client API from the browser: pick an endpoint, edit the path, query and JSON body, send it with your own key, and see the status, timing and response. Sending endpoints are real (they deliver messages and may be billed) and ask for confirmation; "Copy as curl" gives the same call for your own code with `$API_KEY` as the key. The full reference is at `/swagger-ui.html` (also served through the client UI). Integrating from your own system needs only the API key and `X-API-Key`; the UI is optional.

## Billing

Admin UI: **Billing plans** (prices, free allowance, platform fee, tax), **Billing accounts** (assign a plan, postpaid or prepaid, spend cap, top up credit), **Invoices** (generate a closed month, issue, record payment, void, CSV). Client UI: **Billing** (account, usage, invoices, prepaid ledger; invoices print to PDF from the browser).

Prepaid clients get `402 INSUFFICIENT_CREDIT` when a request cannot be reserved; postpaid clients with a cap get `402 SPEND_CAP_EXCEEDED`; suspended accounts get `403`. The dispatcher runs the settlement job (`billing.settlement.*`) and the monthly invoice generator (`billing.invoicing.*`). See ADR-004 for the rules and limits.

## Database migrations

The schema is owned by the `db-migration` job (Liquibase), not by the services. `docker compose --profile app up` runs it
first (`db-migrate`) and the services start only after it succeeds; they run Hibernate `ddl-auto: validate` and refuse to
start against a missing or outdated schema. Changesets live in `db-migration/src/main/resources/db/changelog/`.

```bash
docker compose run --rm db-migrate status        # what is still to be applied
docker compose run --rm db-migrate update-sql    # the exact SQL update would run, without running it
docker compose run --rm db-migrate validate      # changelog valid, and no applied changeset was edited
docker compose run --rm db-migrate history       # what has been applied (and pre-update tags)
docker compose run --rm db-migrate update        # apply (what the stack does automatically)

# undo: refused unless explicitly allowed, because it changes or deletes data
docker compose run --rm -e MIGRATION_ALLOW_ROLLBACK=true db-migrate rollback-count 1
docker compose run --rm -e MIGRATION_ALLOW_ROLLBACK=true db-migrate rollback-tag pre-20260920101500
```

`update` tags the previous state (`pre-<utc time>`) before applying anything, so a bad release can be undone with
`rollback-tag`. **To change the schema:** add a new file under `changes/`, list it at the end of
`db.changelog-master.yaml`, and give every changeset a `--rollback`. Never edit a changeset that has been released
(`validate` will fail). Outside Docker: `java -jar db-migration/target/db-migration-*.jar status` with `DB_URL`,
`DB_USER`, `DB_PASSWORD` set. Databases previously migrated by Flyway are adopted automatically (see ADR-003).

## Static analysis

Sonar findings are kept at zero. To reproduce locally with a throwaway SonarQube (`admin`/`admin`, UI on `:9001`):

```bash
docker run -d --name sonarqube-local -p 9001:9000 -e SONAR_SEARCH_JAVAADDITIONALOPTS=-Dnode.store.allow_mmap=false sonarqube:community
# create a token in the UI (My Account > Security), then:
mvn package org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.host.url=http://localhost:9001 -Dsonar.token=<token> -Dsonar.projectKey=notification-service
```

JaCoCo reports are written to `*/target/site/jacoco/` by `mvn test` and picked up by Sonar.

## Test

```bash
mvn test                       # unit tests; the db-migration tests use a real PostgreSQL via Testcontainers
                               # (they need Docker and are skipped automatically without it)
cd admin-ui && npm run build   # type-check + build the UI
cd client-ui && npm run build  # likewise for the client tracker
```

## License

Copyright 2026 Vasantha Kumar (vasantha.kumar@hotmail.com). Licensed under the [Apache License, Version 2.0](LICENSE); see [NOTICE](NOTICE). Every source file carries the license header and author.
