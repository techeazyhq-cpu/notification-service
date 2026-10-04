# Postman collection

`notification-service.postman_collection.json` walks through the whole product in 51 requests, each with tests:

| Folder | What it shows |
|---|---|
| 1. Admin · Sign in | An administrator signs in; the token is kept for the admin calls |
| 2. Admin · Onboard a tenant | Client and API key, rate limits, a billing plan, a prepaid account with credit, the tenant's own OTP prices |
| 3. Client · Account and error dictionary | The tenant's identity, and the error code dictionary |
| 4. Client · Templates | An OTP template, preview, the template list |
| 5. Client · Send | E-mail with inline text, a one-time password, bulk JSON with an idempotency key (and its safe repeat), bulk CSV upload |
| 6. Client · Track delivery | Request status, failed messages, CSV export, OTP expiry, recent requests, summary |
| 7. Client · Senders, billing and privacy | Sender addresses, the billing view and ledger, retention, the recipient activity report, erasure |
| 8. Client · What errors look like | Missing key, channel not enabled, OTP in a bulk send, missing template value |
| 9. Admin · Operations | Dashboard, the tenant's messages, dead letters, providers, audit log |
| 10. Clean up | Deletes what the run created and disables the tenant, so it can run again |

## Run it

1. Start the stack and seed it:

   ```bash
   docker compose --profile app up -d --build
   ```

   ```bash
   node scripts/seed.mjs
   ```

2. Import into Postman:
   - `notification-service.postman_collection.json`;
   - `local.postman_environment.json`.

   Select the **Notification Service · local** environment.
3. Run the collection from the top with the Collection Runner.
   - **The CSV upload request:** if Postman can't find `postman/recipients.csv`, select that file for its `file`
     field (or set the Postman working directory to the repository root).

Or from the command line, at the repository root:

```bash
npx newman run postman/notification-service.postman_collection.json -e postman/local.postman_environment.json --working-dir .
```

## How it fits together

- **No copying keys between requests.** Folder 2 creates a new tenant and stores its API key in the collection variable
  `apiKey`; the client folders send it as `X-API-Key`. To call the client API as an existing tenant instead, set
  `apiKey` to that tenant's key and run folders 3 to 8.
- **Signed requests.** Folder 2 also issues the tenant a signing secret, stored in `signingSecret`.
  - From then on, the collection's pre-request script signs every client API write: method, path, query and body,
    with a timestamp and a single-use nonce (ADR-036). The platform verifies each signature.
  - The CSV upload is the one exception. Postman builds `form-data` bodies only while sending, so they cannot be
    signed in advance. It is sent unsigned, which is accepted while signing is optional.
  - To call as an existing tenant that signs, set its `apiKey` and `signingSecret`. Clear `signingSecret` to send
    unsigned.
- **Every run is independent.** Names carry a per-run id, so runs never collide.
- **Other environments.** Copy the environment and change:
  - `clientApiUrl` and `adminApiUrl`;
  - `adminUsername` and `adminPassword`. Outside local development, the administrator must have changed the initial
    password, and needs `verificationCode` in the sign-in body once two-factor authentication is on.
- **Expected failure.** The local test provider rejects any SMS recipient ending in `0400`. The bulk request uses one
  such number, to show a failure.

More: [client guide](../docs/client-guide.md), [tenant onboarding](../docs/tenant-onboarding.md),
[error codes](../docs/error-codes.md), and the client API reference at `/swagger-ui.html`.
