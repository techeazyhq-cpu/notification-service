# notification-service

Generic multi-channel notification platform: **Email, SMS, WhatsApp, App Push**, built on **Apache Pulsar**.

- **Client API** (`:8080`): REST for single and bulk sends (JSON body or CSV upload) and request/message status queries. Swagger UI at `/swagger-ui.html`.
- **Client UI** (`:5174`): sign in with a client API key to follow your requests: summary, per-request progress with live updates, per-recipient outcomes, search, CSV export of failed recipients.
- **Admin API** (`:8081`) + **Admin UI** (`:5173`): clients and API keys, templates, providers, rate limits, dashboard, failed-message retry.
- **Dispatcher** (`:8082`): Pulsar consumers that rate-limit, render, send, retry and fail over between providers.
- Local catchers: **Mailpit** (email, UI `:8025`) and a small **catcher** for SMS/WhatsApp/Push (UI `:9000`).

Design: [docs/design.md](docs/design.md) · Decision: [docs/adr-001-modular-monolith-pulsar-outbox.md](docs/adr-001-modular-monolith-pulsar-outbox.md)

## Run it

Requires Docker, and Node 20+ for the seed script and UI.

```bash
docker compose --profile app up -d --build     # postgres, redis, pulsar, mailpit, catcher + 3 services
node scripts/seed.mjs                          # providers, templates, demo client (prints its API key once)
cd admin-ui && npm install && npm run dev      # http://localhost:5173  (admin / admin)
cd client-ui && npm install && npm run dev     # http://localhost:5174  (sign in with the demo client API key)
```

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
| `notification-core` | Domain entities, Flyway schema, repositories, Pulsar publisher, Redis rate limiter, outbox sweeper |
| `client-api` / `admin-api` / `dispatcher` | The three Spring Boot deployables |
| `admin-ui` | React + Vite admin SPA |
| `client-ui` | React + Vite tracking SPA for API clients (read-only) |
| `tools/catcher` | SMS/WhatsApp/Push gateway stand-in |
| `docs` | Design, ADR, draw.io container diagram |

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
mvn test                       # unit tests (no infrastructure needed)
cd admin-ui && npm run build   # type-check + build the UI
cd client-ui && npm run build  # likewise for the client tracker
```
