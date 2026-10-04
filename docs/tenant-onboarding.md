# Onboarding a tenant

A tenant (a *client* in the APIs) is an organisation that sends notifications through the platform from its own
systems. This runbook takes one from first contact to its first delivered message. Steps 1–6 are for a platform
operator, who needs an **`ADMIN`** account in the admin console; step 5 can also be done by an `OPERATOR`. Steps
7–9 are done by the tenant, with the operator on hand to help.

Every step was checked end to end against the local stack (`docker compose --profile app up`). Each one shows the admin
console path first and the equivalent API call after it, for operators who script onboarding.

| # | Who | Step | Required? |
|---|---|---|---|
| 1 | Operator | [Collect the tenant's details](#1-collect-the-tenants-details) | yes |
| 2 | Operator | [Create the client and its API key](#2-create-the-client-and-its-api-key) | yes |
| 3 | Operator | [Set its rate limits](#3-set-its-rate-limits) | recommended |
| 4 | Operator | [Put it on a billing plan](#4-put-it-on-a-billing-plan) | only if it is billed |
| 5 | Operator | [Top up prepaid credit](#5-top-up-prepaid-credit) | prepaid only |
| 6 | Operator | [Set its OTP prices](#6-set-its-otp-prices) | optional |
| 7 | Operator | [Hand over access](#7-hand-over-access) | yes |
| 8 | Tenant | [Set up sender addresses and templates](#8-the-tenant-sets-up-senders-and-templates) | as needed |
| 9 | Both | [Send a first message and check it](#9-send-a-first-message-and-check-it) | yes |

Local URLs are used below. In other environments, use that environment's admin console, client console and client API
addresses.

| What | Local URL |
|---|---|
| Admin console | http://localhost:5173 |
| Client console | http://localhost:5174 |
| Client API and its reference | http://localhost:8080, http://localhost:8080/swagger-ui.html |

## 1. Collect the tenant's details

Agree these with the tenant before creating anything:

| Detail | Example | Used in step |
|---|---|---|
| Name, unique on the platform | `acme` | 2 |
| Channels it may use: `EMAIL`, `SMS`, `WHATSAPP`, `PUSH` | `EMAIL`, `SMS` | 2 |
| Expected API request rate, and peak sending rate per channel | 20 requests/s; 10 SMS/s | 3 |
| Whether it is billed, and how: **postpaid** (invoiced monthly) or **prepaid** (pays for credit first) | prepaid | 4, 5 |
| Its plan and currency, or the prices to build one from | `standard`, EUR | 4 |
| For postpaid: a monthly spend cap, if any | 500 EUR | 4 |
| Billing contact e-mail | `billing@acme.com` | 4 |
| Whether it sends one-time passwords (login or verification codes), and any negotiated price for them | SMS OTP at 0.012 EUR | 6 |
| The addresses it will send e-mail from | `orders@acme.com` | 8 |
| A named person to receive the API key | | 7 |

A tenant without a billing account is not billed at all. Decide that on purpose, not by omission.

## 2. Create the client and its API key

**Admin console → Clients.** Enter the **Name**, tick the **channels** the tenant may use, and create it.

- **The API key is shown once, now.** It starts with `ntf_`. Copy it straight into the secure channel you will hand it
  over with (step 7). The platform stores only a hash of it and cannot show it again.
- A new client is `ACTIVE`. Sending on a channel that isn't ticked is refused with `403 CHANNEL_NOT_ALLOWED`
  (NS-2008).

API:

```bash
curl -X POST "$ADMIN_API/api/admin/clients" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"acme","allowedChannels":["EMAIL","SMS"]}'
```

The answer holds the client's `id` and, once only, its `apiKey`. Keep the `id` for the following steps.
(`$ADMIN_TOKEN` comes from `POST /api/admin/auth/login`.)

## 3. Set its rate limits

**Admin console → Rate limits.** Add a policy per limit, choosing the tenant as **Client**:

| Scope | What it limits | If you set nothing |
|---|---|---|
| **Client API calls (requests/s)** | Every call the tenant makes to the client API | 50 requests/s, burst 100 |
| **Client delivery per channel (messages/s)** | How fast its messages go to providers on one channel | No per-tenant limit; only the platform-wide limit for the channel applies |

- **Rate / second** is the sustained rate. **Burst** is how many may go at once after a quiet spell.
- A per-channel delivery limit stops one tenant's large send from using a channel's whole provider capacity.
  Set one for every channel with a provider throughput contract.
- Over the API limit, a call gets `429 RATE_LIMITED` (NS-5001) with `Retry-After`.
- Over a delivery limit, messages wait their turn; they are not refused.
- One-time passwords may use the last 20 % of every delivery bucket, which other messages must leave free (ADR-033).

API, one call per policy:

```bash
curl -X POST "$ADMIN_API/api/admin/rate-limits" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"scope":"CLIENT_CHANNEL","clientId":"<client id>","channel":"SMS","ratePerSecond":10,"burst":20,"enabled":true}'
```

## 4. Put it on a billing plan

Skip this step if the tenant is not billed.

**a. Choose or create a plan: Admin console → Billing plans.**

- Reuse an existing plan where its prices fit.
- To create one, set its currency, a monthly platform fee (postpaid only) and a tax rate. Then, per channel, set the
  price per message and the monthly free allowance (postpaid only).
- A channel without a price is free.
- A plan's currency cannot change once an account uses it.

**b. Create the billing account: Admin console → Billing accounts.**

- Choose the **Client**, the **Plan** and the **Mode**:
  - **Postpaid:** invoiced after each month for the messages that were sent. Optionally set a **Monthly spend cap**:
    a request that would take the month past it is refused with `402 SPEND_CAP_EXCEEDED` (NS-4002). The cap is soft:
    it counts messages already sent, so a burst can overshoot it by what is still queued.
  - **Prepaid:** every request reserves its cost from credit when it is accepted, and what isn't sent is refunded
    once the request finishes. Without enough credit a request is refused with `402 INSUFFICIENT_CREDIT` (NS-4001).
- Set **Status** to `ACTIVE` and enter the **Billing email**.

API:

```bash
curl -X PUT "$ADMIN_API/api/admin/billing/accounts/<client id>" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"planId":"<plan id>","mode":"PREPAID","status":"ACTIVE","billingEmail":"billing@acme.com"}'
```

## 5. Top up prepaid credit

Prepaid only. An `OPERATOR` may do this step too.

**Admin console → Billing accounts**, in the credit form:

1. Choose the tenant under **Prepaid client** and set **Type** to **Top-up (payment received)**.
2. Enter the **Amount**.
3. Enter a **Reference**, such as the payment's bank reference. Applying the same reference twice changes nothing,
   so a double click can't add the money twice.
4. Apply.

The new balance is shown; every movement is in the tenant's ledger.

API:

```bash
curl -X POST "$ADMIN_API/api/admin/billing/accounts/<client id>/credit" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"kind":"TOP_UP","amount":50,"reference":"PAY-2026-0001","description":"Initial credit"}'
```

## 6. Set its OTP prices

Optional, and only for a tenant with a billing account. Skip it to charge one-time passwords at the plan's price.

**Admin console → Billing accounts → OTP prices** on the tenant's row:

- Enter the tenant's price per one-time password for each channel where it differs from the plan. The plan's price is
  shown next to each channel; leave a channel blank to use it. Prices are in the plan's currency.
- **Set OTP prices at or above the plan's price.** The tenant decides which messages are OTPs, and nothing verifies
  it, so a lower price would reward labelling ordinary messages as OTPs (ADR-033, ADR-034).
- **How they apply:** a prepaid OTP request reserves credit at the OTP price. On a postpaid invoice, OTPs get a line of
  their own. The channel's free allowance covers ordinary messages first, then OTPs. A new price applies to OTPs
  accepted from then on, and to any invoice still in draft.

API, which replaces the whole set; an empty list removes them all:

```bash
curl -X PUT "$ADMIN_API/api/admin/billing/accounts/<client id>/otp-prices" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"prices":[{"channel":"SMS","unitPrice":0.012}]}'
```

## 6a. Issue a signing secret (if the tenant signs its requests)

Do this step if the tenant wants protection against replayed or altered requests: banks, healthcare and public
sector tenants usually do. Otherwise skip it; the tenant can start later.

**Admin console → Clients → Issue signing secret.**

- **The secret is shown once, now.** It starts with `nss_`. Hand it over in step 7 with the API key, through the same
  secure channel.
- **Signing stays optional at first.** The platform verifies any request that carries a signature, but still accepts
  unsigned ones.
- **Require signatures** once the tenant confirms its integration signs every request (the client guide's *Signed
  requests* section has code). From then on, an unsigned `POST`, `PUT` or `DELETE` is refused with
  `401 SIGNATURE_REQUIRED` (NS-2011). Reads stay unsigned.
- **Rotate signing secret** replaces the secret at once. Requests signed with the old one fail within 30 seconds, so
  agree the moment with the tenant.
- **Remove signing secret** turns signing off again.

API:

```bash
curl -X POST "$ADMIN_API/api/admin/clients/$CLIENT_ID/signing-secret" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -X PUT "$ADMIN_API/api/admin/clients/$CLIENT_ID/signing-required" -H "Authorization: Bearer $ADMIN_TOKEN"   -H "Content-Type: application/json" -d '{"required":true}'
```

## 7. Hand over access

Give the tenant's named contact:

- **The API key,** through a channel meant for secrets, such as a password manager share or a one-time secret link.
  Never send it in e-mail or chat. It is what authenticates every call, as the `X-API-Key` header.
- **The client console address.** They sign in there with the API key. It shows their requests, templates, sender
  addresses, billing and an API playground. The key is kept in the browser tab only and is gone when the tab closes.
- **The client API address and its reference** (`/swagger-ui.html`), and the
  [error code dictionary](error-codes.md), which the API also serves at `GET /v1/errors`.
- **The [client guide](client-guide.md),** which walks their team through the console and the API.
- **The signing secret,** if you issued one in step 6a, through the same channel as the API key. It is also pasted
  into the console at sign-in.

## 8. The tenant sets up senders and templates

The tenant does these in the client console; the operator only helps.

**Sender addresses** (e-mail only): **Client console → Sender addresses.** The page appears only for a tenant
allowed to send `EMAIL`.

- Add the address and a display name. The platform e-mails a confirmation link to that address, and the address can
  be used once someone there opens the link. It can then be the default sender, or be chosen per request with
  `"from"`.
- A request naming an unconfirmed address is refused with `422 SENDER_NOT_VERIFIED` (NS-3007).
- **Operator check:** the link points at the client API's public address, `CLIENT_API_PUBLIC_BASE_URL`. If tenants
  report a link that doesn't open, that setting is wrong for the environment.

**Templates:** **Client console → Templates.**

- Shared templates from the platform are listed read-only; the tenant can copy one as its own.
- Its own templates use `{{name}}` placeholders, filled per recipient. `{{recipient}}` is always available.
- For login or verification codes, set the template's **Category** to **One-time password**. Every send from it is
  then delivered with priority and never after it expires (5 minutes by default; 1–15 minutes per request with
  `"validitySeconds"`).
- OTPs are single sends only: a bulk send with an OTP template is refused with `400 CATEGORY_NOT_ALLOWED` (NS-1005).

## 9. Send a first message and check it

From the client console's **API playground**, or from the tenant's own system:

```bash
curl -X POST "$CLIENT_API/v1/notifications" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"channel":"SMS","recipient":"+14155550199","templateName":"login-code","variables":{"code":"731904"}}'
```

The answer is `202 Accepted` with a `requestId`. Then check:

| Check | Where | Expected |
|---|---|---|
| The message was delivered | Client console → the request; or `GET /v1/notifications/{requestId}/messages` | `SENT`. A one-time password also shows its category and expiry |
| The tenant sees its prices | Client console → Billing; or `GET /v1/billing/account` | The plan's prices, and the OTP price next to the channel if set in step 6 |
| Prepaid: credit was reserved and settled | Client console → Billing → ledger; Admin console → Billing accounts → Ledger | A `HOLD` for the message's price, taken from the balance, settled about 10 seconds after the message finishes; any unused part comes back as a refund |
| The operator sees the traffic | Admin console → Messages, filtered by the client | The message, with an `OTP` tag if it was one |

The consoles show money to two decimals. The platform keeps exact amounts to six decimals. For example, a 0.012 EUR
OTP shows as a `-0.01` hold, but exactly 0.012 is charged.

If the first send fails, look up the error id it carries:

| Answer | Code | Fix |
|---|---|---|
| 401 | `UNAUTHORIZED` (NS-2001) | Wrong or missing `X-API-Key`, or the client is disabled |
| 403 | `CHANNEL_NOT_ALLOWED` (NS-2008) | Tick the channel on the client (step 2) |
| 403 | `ACCOUNT_SUSPENDED` (NS-4003) | The billing account is suspended (step 4) |
| 402 | `INSUFFICIENT_CREDIT` (NS-4001) | Top up (step 5) |
| 402 | `SPEND_CAP_EXCEEDED` (NS-4002) | Raise the cap or wait for the next month (step 4) |
| 422 | `SENDER_NOT_VERIFIED` (NS-3007) | Open the confirmation link (step 8) |
| 429 | `RATE_LIMITED` (NS-5001) | Slow down, or raise the API limit (step 3) |
| Message `FAILED`, `DELIVERY_REJECTED` (NS-6001) | | The provider refused the recipient; its own words are in the message's `lastError` |

## After onboarding

| Task | Where |
|---|---|
| **Replace a lost or leaked API key** | Admin console → Clients → Rotate key. The new key is shown once, and the old one stops working within 30 seconds. Hand it over as in step 7. |
| **Stop all sending at once** | Admin console → Clients → Disable. Every call then gets `401`. |
| **Stop sending for billing reasons only** | Admin console → Billing accounts → status `SUSPENDED`. Sends get `403 ACCOUNT_SUSPENDED`; everything else still works. |
| **Change channels, limits, plan or OTP prices** | Steps 2, 3, 4 and 6 again. Billing mode and currency can't change while a prepaid account holds credit or has a request still settling. |
| **Invoice a postpaid tenant** | Admin console → Invoices. Generate a closed month, review the draft, issue it, then record the payment. |
| **Every change is recorded** | Admin console → Audit log: who changed what, and when (ADR-019). |
