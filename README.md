# notification-service

Generic multi-channel notification platform: **Email, SMS, WhatsApp, App Push**, built on **Apache Pulsar**.

- **Client API** (`:8080`): REST for single and bulk sends (JSON body or CSV upload) and request/message status queries. Swagger UI at `/swagger-ui.html`.
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

## Layout

| Path | What |
|---|---|
| `notification-core` | Domain entities, Flyway schema, repositories, Pulsar publisher, Redis rate limiter, outbox sweeper |
| `client-api` / `admin-api` / `dispatcher` | The three Spring Boot deployables |
| `admin-ui` | React + Vite admin SPA |
| `tools/catcher` | SMS/WhatsApp/Push gateway stand-in |
| `docs` | Design, ADR, draw.io container diagram |

## Test

```bash
mvn test                       # unit tests (no infrastructure needed)
cd admin-ui && npm run build   # type-check + build the UI
```
