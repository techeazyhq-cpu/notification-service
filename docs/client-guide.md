# Client guide: sending notifications through the platform

This guide is for a tenant's team once the platform operator has onboarded it ([tenant-onboarding.md](tenant-onboarding.md)).
[Part 1](#part-1-the-client-console) is for anyone who follows deliveries and manages templates in the **client
console**. [Part 2](#part-2-integrating-with-the-client-api) is for developers connecting their own systems to the
**client API**.

Every step and example was checked against a running stack. Local addresses are used below; in your environment, use
the addresses your operator gave you.

| What | Local address |
|---|---|
| Client console | http://localhost:5174 |
| Client API | http://localhost:8080 |
| API reference (try every call in the browser) | http://localhost:8080/swagger-ui.html |

## What you receive from the operator

- **An API key,** starting with `ntf_`. It identifies your organisation on every call and is your sign-in for the
  console. Treat it like a password: keep it in a secrets store, never in source code, e-mail or chat. If it may have
  leaked, ask the operator to rotate it. The old key then stops working within 30 seconds.
- **A signing secret,** starting with `nss_`, if your organisation signs its requests against replay (see
  [Signed requests](#signed-requests-protection-against-replay)). Keep it as safely as the API key.
- **The channels you may use:** some of `EMAIL`, `SMS`, `WHATSAPP` and `PUSH`.
- **Your limits and prices,** which you can look up at any time in the console under **Billing**.

## Concepts in one minute

- **A request** is one call to send: to a single recipient, or to many at once (a *bulk* request).
- **A message** is one recipient of a request. A bulk request to 10,000 people is one request with 10,000 messages,
  each with its own status.
- **A template** holds the text, with `{{placeholders}}` filled per recipient. `{{recipient}}` (the recipient's
  address) is always available. You can also send without a template, giving the text in the request.
- **Recipients** are written per channel:

  | Channel | Recipient | Example |
  |---|---|---|
  | `EMAIL` | E-mail address | `ann@example.com` |
  | `SMS`, `WHATSAPP` | Phone number in international format | `+14155550123` |
  | `PUSH` | The device token from your app | `device-token` |

- **A category** tells the platform what a message is for, which decides how it is delivered:

  | Category | Use it for | What it changes |
  |---|---|---|
  | `TRANSACTIONAL` (default) | Receipts, alerts, account notices | Nothing |
  | `PROMOTIONAL` | Marketing | Nothing |
  | `OTP` | One-time passwords: login or verification codes | Delivered ahead of other traffic, and **never after it expires**: 5 minutes by default, 1–15 minutes if you choose. Single sends only. May be priced differently: see **Billing** |

- **Message statuses:**

  | Status | Meaning |
  |---|---|
  | `PENDING`, `QUEUED`, `PROCESSING` | Accepted and on its way |
  | `RETRYING` | The provider failed temporarily. The platform tries again automatically, waiting longer each time, up to 5 attempts |
  | `SENT` | The SMS, e-mail or messaging provider accepted it. That is not the same as the recipient's phone or inbox confirming it |
  | `FAILED` | It won't be delivered. Its error code says why: see [What a failure means](#what-a-failure-means) |

- **A request's status** sums up its messages:

  | Status | Meaning |
  |---|---|
  | `PROCESSING` | Some messages are still on their way |
  | `COMPLETED` | Every message was sent |
  | `PARTIALLY_FAILED` | Some were sent and some failed |
  | `FAILED` | None was sent |

## Part 1: the client console

### Sign in and out

Open the console and paste your API key into **API key**, and your signing secret, if you have one, into
**Signing secret**. Then **Sign in**. Both are kept only in the open page, never saved in the browser:
reloading the page or closing the tab signs you out. **Sign out** in the top bar signs you out at once.

The pages you see depend on your access. **Sender addresses**, for example, appears only if you may send e-mail.

### Requests: follow your deliveries

**Requests** is the home page.

- **Overview:** the numbers of requests, messages, sent and failed, and your success rate, plus a breakdown **By
  channel**.
- **List:** your requests, newest first, with their progress and status.
- **Filters:** narrow the list by **Channel**, find one by **Your reference contains** (the `clientReference` your
  system sent), or paste an id into **Find a request by ID** and **Open** it.

Open a request to see its details:

- **Summary:** its channel, type (single or bulk), when you sent it, your reference, a progress bar and counts per
  status.
- **Category and expiry:** its category and, for a one-time password, **Valid until**: the moment after which it is
  never sent.
- **Live updates:** "Updating live…" shows while messages are still on their way.
- **Messages:** one row per recipient with its status and attempts. Filter by **Status** or **Recipient contains**.
  For a failed message, the detail starts with its error id (e.g. `NS-6001`), which links to the explanation.
- **Downloads:**
  - **Download all (CSV)** gives every recipient with its status.
  - **Download failed (CSV)** gives only the recipients that failed, ready to correct and send again.

### Templates

**Templates** lists your own templates and the platform's shared ones.

- **Shared templates** are read-only. **View** one, and **Copy as my own template** to change it.
- **New template:**
  1. Fill in the **Name** (letters, digits, `.`, `-`, `_`), the **Channel**, and a **Subject / title** (required for
     e-mail).
  2. Write the **Body**, using `{{name}}` placeholders.
  3. Choose the **Category**. Pick **One-time password** for login and verification codes: every send from the
     template is then an OTP, unless a request says otherwise.
  4. **Create template**.
- **Preview** shows the text as a recipient sees it. Type sample values for each placeholder; any still missing are
  listed.
- **Send with this template** shows the exact request your system should make to send with it.
- **The channel** can't change after creation. Create a new template instead.
- **Your template wins.** If your template has the same name as a shared one, sends use yours.
- **Editing never touches messages already accepted.** Every request keeps the text it was sent with.

### Sender addresses (e-mail)

E-mail goes out from the platform's address unless you register your own.

1. Under **Sender addresses**, enter the **E-mail address** and an optional **Display name**, then **Add and send
   confirmation**.
2. Someone with access to that mailbox opens the link in the confirmation e-mail. The address turns `VERIFIED`.
   **Resend** sends another link if the first one is lost.
3. **Make default** to send from it whenever a request doesn't name a sender. A request can also name any verified
   address with `"from"`.

**Remove** deletes an address. Sending from an address that isn't verified is refused with `SENDER_NOT_VERIFIED`.

### API playground

**API playground** lets you try any client API call from the browser with your own key:

1. Pick an endpoint.
2. Edit its path, query and JSON body.
3. Send it, and read the status, timing and response.

Calls that send messages are real: they deliver, and may be billed, so the playground asks you to confirm first.
**Copy as curl** gives the same call for your own code, with `$API_KEY` in place of your key. **This session** lists
the calls you made.

### Billing

**Billing** appears if your organisation is billed.

- **Plan:** your plan name, how you pay (prepaid or postpaid), your credit balance (prepaid) or monthly spend cap
  (postpaid), the platform fee and tax.
- **Prices per channel:** the price per message and the monthly free allowance. Where your organisation has its own
  price for one-time passwords, it appears under **Price per one-time password**. One-time passwords then appear on a
  line of their own, and the free allowance covers your other messages first.
- **Usage and cost:** what a **Month** has cost so far. The current month is an estimate until it ends.
- **Invoices** (postpaid): issued invoices, with amounts due. Download one as **CSV**, or open it and print it to PDF
  from the browser.
- **Credit ledger** (prepaid): every top-up, reservation and refund.

**How prepaid works.** When you send, the cost of the whole request is reserved from your credit. About 10 seconds
after its last message finishes, what was actually sent is charged and the rest is returned. Amounts show to two
decimals but are kept exactly: a 0.012 charge shows as `-0.01`.

### Privacy and data retention

**Privacy and data retention** shows **How long recipient data is kept**:

- **Recipient data** (addresses, template values, error text) is erased after **90 days**.
- **Message records** are deleted after **400 days**. Until then each record keeps its event log and a fingerprint of
  the recipient's address. The fingerprint can't be turned back into the address, but lets you find the record when
  someone gives you their address again (see below).
- **An idempotency key** stops working after **7 days**.

Your operator may have set different periods; the page shows the ones that apply to you.

**Recipient activity report.** When someone complains to a data protection authority about messages from you, the
authority will ask what you sent them, when and why. Enter their e-mail address, phone number or device token, and
optionally a date range, then **Download CSV**:

- **One row per event** of every message you sent them, oldest first, with UTC timestamps: `ACCEPTED`, each failed
  attempt (`ATTEMPT_FAILED`), `SENT` or `FAILED`, `EXPIRED` for an OTP, `DEAD_LETTERED`, `REQUEUED` when the operator
  resent it, and `DATA_ERASED`.
- **What each message was for:** its category, template, your `clientReference`, request id, channel and sender
  address.
- **How it went:** attempt number, error code and id, and the provider's message id.
- **Found even after erasure.** Messages are found even after the recipient's data was erased; the address then reads
  `[erased]`.
- **Never the message text,** so no one-time password code ends up in the file.
- **Older messages:** a message from before the event log existed has `source` `RECORD`, and only its final outcome.

Spellings don't matter: `Ann@Example.com` finds `ann@example.com`, and `+1 (415) 555-0123` finds `+14155550123`.

**Erase a recipient's data now** removes one recipient's address, values and error text from every finished message
you sent to them, for example after a data-protection request.

- Enter their e-mail address, phone number or device token and **Erase**.
- Messages still on their way are left untouched; repeat the erasure once they finish.
- Erased messages keep their status and counts, but can't be resent.

## Part 2: integrating with the client API

### Authentication

Send your key in the `X-API-Key` header on every call. JSON bodies need `Content-Type: application/json`.

```bash
curl "$CLIENT_API/v1/me" -H "X-API-Key: $API_KEY"
```

`GET /v1/me` returns your organisation's id, name and allowed channels. It's a good first call to check a new key.

### Signed requests (protection against replay)

Your operator can give your organisation a **signing secret** (starting `nss_`). With it you sign each request, and
the platform then refuses:

- a captured request that is sent again;
- a request changed on the way;
- a request whose timestamp is more than 5 minutes from the platform's clock.

Signing is optional until your operator **requires** it. From then on, every `POST`, `PUT` and `DELETE` must be signed,
or it is refused with `401 SIGNATURE_REQUIRED`. Reads (`GET`) never need a signature. Keep the secret as safely as the
API key; it is shown only once when issued. The design is in [ADR-036](adr-036-signed-client-requests.md).

Send three headers with the API key:

| Header | Value |
|---|---|
| `X-Signature-Timestamp` | The current time in Unix seconds, such as `1767225600` |
| `X-Signature-Nonce` | A new random value for **every** request, 16–64 letters, digits, `-` or `_`, such as 32 hex characters |
| `X-Signature` | The lower-case hex HMAC-SHA256 of the text below, keyed by your signing secret |

The signed text is these seven lines joined by a line feed (`\n`), with no line feed at the end:

```text
NS1-HMAC-SHA256
<the X-Signature-Timestamp value>
<the X-Signature-Nonce value>
<the HTTP method in capitals, such as POST>
<the path exactly as sent, such as /v1/notifications>
<the query string exactly as sent, without the "?"; an empty line if there is none>
<the lower-case hex SHA-256 of the exact body bytes; of no bytes if there is no body>
```

Sign the bytes you actually send. Serialise your JSON once, sign that text, and send the same text: re-serialising
after signing can change spacing or field order, and the signature then fails with `SIGNATURE_INVALID`. A CSV upload
is signed over the whole `multipart/form-data` body, boundaries included, so build the body before signing it.

**Check your implementation** against this example: secret `nss_test`, timestamp `1767225600`, nonce
`0123456789abcdef`, `POST /v1/notifications` with the body `{"channel":"SMS"}`. It must give the signature
`2295ae2e9e31c56816963cbcc04632396fa6471d25a8cb667f9003711e053503`.

**Shell** (bash, `openssl` and `curl`):

```bash
# Signs and sends one request. Usage: signed_curl METHOD PATH[?QUERY] [BODY_FILE]
signed_curl() {
  local method=$1 target=$2 body_file=${3:-/dev/null}
  local path=${target%%\?*} query=""
  [[ $target == *\?* ]] && query=${target#*\?}
  local timestamp nonce body_hash canonical signature
  timestamp=$(date +%s)
  nonce=$(openssl rand -hex 16)
  body_hash=$(openssl dgst -sha256 -r < "$body_file" | cut -d' ' -f1)
  canonical=$(printf 'NS1-HMAC-SHA256\n%s\n%s\n%s\n%s\n%s\n%s' \
    "$timestamp" "$nonce" "$method" "$path" "$query" "$body_hash")
  signature=$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$SIGNING_SECRET" -r | cut -d' ' -f1)
  curl -sS -X "$method" "$CLIENT_API$target" -H "X-API-Key: $API_KEY" \
    -H "X-Signature-Timestamp: $timestamp" -H "X-Signature-Nonce: $nonce" -H "X-Signature: $signature" \
    -H "Content-Type: application/json" --data-binary @"$body_file"
}

signed_curl POST /v1/notifications message.json
```

**Python:**

```python
import hashlib
import hmac
import os
import time
from urllib.parse import urlsplit


def signature_headers(secret: str, method: str, url: str, body: bytes = b"") -> dict:
    """Headers that sign one request; `body` must be the exact bytes you send."""
    parts = urlsplit(url)
    timestamp = str(int(time.time()))
    nonce = os.urandom(16).hex()
    canonical = "\n".join(["NS1-HMAC-SHA256", timestamp, nonce, method.upper(), parts.path, parts.query,
                           hashlib.sha256(body).hexdigest()])
    signature = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {"X-Signature-Timestamp": timestamp, "X-Signature-Nonce": nonce, "X-Signature": signature}


body = json.dumps(payload).encode()
requests.post(url, data=body, headers={"X-API-Key": api_key, "Content-Type": "application/json",
                                       **signature_headers(signing_secret, "POST", url, body)})
```

**Node.js** (18 or later):

```js
import { createHash, createHmac, randomBytes } from 'node:crypto';

/** Headers that sign one request; `body` must be the exact bytes (or string) you send. */
export function signatureHeaders(secret, method, url, body = '') {
  const { pathname, search } = new URL(url);
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = randomBytes(16).toString('hex');
  const canonical = ['NS1-HMAC-SHA256', timestamp, nonce, method.toUpperCase(), pathname, search.slice(1),
    createHash('sha256').update(body).digest('hex')].join('\n');
  const signature = createHmac('sha256', secret).update(canonical).digest('hex');
  return { 'X-Signature-Timestamp': timestamp, 'X-Signature-Nonce': nonce, 'X-Signature': signature };
}

const body = JSON.stringify(payload);
await fetch(url, {
  method: 'POST', body,
  headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json', ...signatureHeaders(signingSecret, 'POST', url, body) },
});
```

**Java** (17 or later):

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Headers that sign one request; {@code body} must be the exact bytes you send. */
final class RequestSigner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    static Map<String, String> signatureHeaders(String secret, String method, URI uri, byte[] body)
            throws GeneralSecurityException {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String nonce = HEX.formatHex(random);
        String canonical = String.join("\n", "NS1-HMAC-SHA256", timestamp, nonce, method.toUpperCase(Locale.ROOT),
                uri.getRawPath(), uri.getRawQuery() == null ? "" : uri.getRawQuery(),
                HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        return Map.of("X-Signature-Timestamp", timestamp, "X-Signature-Nonce", nonce, "X-Signature", signature);
    }
}
```

What can go wrong:

| Error | Why | What to do |
|---|---|---|
| `401 SIGNATURE_REQUIRED` (NS-2011) | Signatures are required and the request was unsigned | Sign every request that changes something |
| `401 SIGNATURE_INVALID` (NS-2012) | A header is missing or malformed, you have no secret, or the signature doesn't match | Compare your signed text with the format above, line by line; check the secret is the current one |
| `401 SIGNATURE_EXPIRED` (NS-2013) | Your timestamp is more than 5 minutes from the platform's clock | Sign when you send; keep your clock synchronised (NTP) |
| `409 REQUEST_REPLAYED` (NS-2014) | This nonce was already used | Use a new nonce, and therefore a new signature, for every attempt |

**Retries:** sign every attempt afresh, with a new timestamp and nonce, and keep the same `Idempotency-Key`. The
platform then returns the original request rather than sending twice.

**In the console:** if your organisation has a signing secret, paste it under **Signing secret** when you sign in.
Everything you change in the console and the API playground is then signed for you. Leave the field empty if you have
no secret.

**When the secret changes:** a new secret replaces the old one, and requests signed with the old one are refused
within 30 seconds. Agree a moment with your operator, and switch your configuration at that moment.

### Send to one recipient

With a template (`templateName`) and its values (`variables`):

```bash
curl -X POST "$CLIENT_API/v1/notifications" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"channel":"EMAIL","recipient":"ann@example.com","templateName":"welcome-email","variables":{"name":"Ann"}}'
```

Without a template, give the text itself. E-mail also needs a `subject`. Placeholders work here too:

```bash
curl -X POST "$CLIENT_API/v1/notifications" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"channel":"EMAIL","recipient":"ann@example.com","subject":"Hello {{name}}","body":"Hi {{name}}, order {{order}} shipped.","variables":{"name":"Ann","order":"A-1"},"clientReference":"order-A-1"}'
```

Optional fields:

| Field | What it does |
|---|---|
| `clientReference` | Your own id, up to 120 characters. Shown in the console and searchable |
| `from` | E-mail only: a verified sender address. Your default sender is used if you leave it out |
| `category` | `TRANSACTIONAL`, `PROMOTIONAL` or `OTP`. Defaults to the template's category, else `TRANSACTIONAL` |
| `validitySeconds` | OTP only: 60–900, default 300 |

The answer is **`202 Accepted`**: the request is stored durably and will be delivered. It holds the `requestId`, and
for a single send the `messageIds`. Delivery happens in the background: follow it as in
[Follow delivery](#follow-delivery).

### Send one-time passwords

```bash
curl -X POST "$CLIENT_API/v1/notifications" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"channel":"SMS","recipient":"+14155550123","templateName":"otp-sms","variables":{"code":"482913"},"category":"OTP","validitySeconds":120}'
```

Or give the template the OTP category once, and every send from it is an OTP without saying so.

- **Delivery:** OTPs are delivered ahead of other traffic, even behind a large bulk send.
- **Expiry:** an OTP still unsent when its validity ends is dropped, not sent late. Its message ends `FAILED` with
  `OTP_EXPIRED`, and it can't be resent: ask your user to request a new code.
- **Single sends only:** a bulk request with `"category":"OTP"` is refused with `CATEGORY_NOT_ALLOWED`.

### Send to many recipients

As JSON, each recipient with its own values (up to 50,000 recipients per request):

```bash
curl -X POST "$CLIENT_API/v1/notifications/bulk" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"channel":"SMS","templateName":"otp-sms","clientReference":"campaign-42","recipients":[
        {"recipient":"+14155550101","variables":{"code":"1111"}},
        {"recipient":"+14155550102","variables":{"code":"2222"}}]}'
```

As a CSV file (up to 20 MB). The header row names the columns: `recipient` is the address, and every other column
becomes a template value:

```csv
recipient,name,orderId
+14155550111,Ann,A-1
+14155550112,Bob,B-2
```

```bash
curl -X POST "$CLIENT_API/v1/notifications/bulk/upload" -H "X-API-Key: $API_KEY" \
  -F channel=WHATSAPP -F templateName=order-whatsapp -F file=@recipients.csv
```

`clientReference`, `from`, `subject`, `body` and `category` (`TRANSACTIONAL` or `PROMOTIONAL`) work as form fields too.

Every recipient must have every value the template needs. Otherwise the whole request is refused, naming the first
recipient and value missing, and nothing is sent.

### Send safely more than once: idempotency

If a call times out you can't tell whether it was accepted, so send it again with the same **`Idempotency-Key`**
header:

```bash
curl -X POST "$CLIENT_API/v1/notifications/bulk" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: campaign-42-attempt" -d @campaign.json
```

- A repeat with a key already used returns the original request (`"idempotentReplay": true`) and sends nothing again.
- Use a new key for every distinct send, such as your order or campaign id.
- Keys are remembered for 7 days (see [Privacy](#privacy-and-data-retention)).

### Follow delivery

| Call | Returns |
|---|---|
| `GET /v1/notifications/{requestId}` | The request's status and its counts per message status |
| `GET /v1/notifications/{requestId}/messages?status=FAILED&recipient=555&page=0&size=50` | Its messages, filterable and paged. Each has its status, attempts, `errorCode`, `errorId`, `lastError` (the provider's own words), and, for OTPs, `category` and `expiresAt` |
| `GET /v1/notifications/{requestId}/messages/export?status=FAILED` | The same as CSV: recipient, status, attempts, lastError, providerMessageId, sentAt, errorCode, errorId |
| `GET /v1/notifications?channel=SMS&clientReference=campaign` | Your recent requests, newest first |
| `GET /v1/notifications/summary?hours=24` | Message counts per channel and status over the last 1–720 hours |

There are no delivery callbacks yet. Poll the request: a few seconds apart for single sends, and less often for large
bulk sends.

A request is finished when it is no longer `PROCESSING`.

**Duplicates are possible, but rare.** A message is delivered at least once: after a rare internal failure, a recipient
may receive the same message twice. Make the code your system checks tolerate that; for example, accept an OTP code
once, then expire it.

### Manage templates through the API

| Call | Does |
|---|---|
| `GET /v1/templates` | Lists yours and the shared ones (`scope` `OWNED` or `SHARED`; shared ones are `readOnly`), with the placeholders each needs |
| `POST /v1/templates` | Creates one: `name`, `channel`, `subject` (e-mail), `body`, optional `category`. Up to 200 per organisation |
| `PUT /v1/templates/{id}` | Changes one. The channel can't change; leaving out `category` keeps the current one |
| `DELETE /v1/templates/{id}` | Deletes one. Requests already accepted keep their text |
| `POST /v1/templates/preview` | Renders a `subject` and `body` with sample `variables`, and lists the placeholders still missing |

### Errors

Every error has the same shape:

```json
{
  "code": "INSUFFICIENT_CREDIT",
  "message": "Insufficient credit: ...",
  "errorId": "NS-4001",
  "category": "BILLING",
  "retryable": false,
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "docs": "/v1/errors/NS-4001"
}
```

- **Branch on `code`.** It never changes. The `message` is for people and may be reworded.
- **`retryable`** tells you whether the same call, unchanged, can succeed later.
- **`errorId`** and **`traceId`** are what to quote to the operator's support. The trace id leads them straight to
  your request in their logs.
- **`docs`** and `GET /v1/errors/{errorId or code}` explain the cause and the fix. `GET /v1/errors` lists them all,
  and the complete list is in the [error code dictionary](error-codes.md).

The ones you are most likely to meet:

| HTTP | Code | Meaning | What to do |
|---|---|---|---|
| 400 | `INVALID_REQUEST` (NS-1000) | Something in the request is wrong, for example an unknown template or a missing template value | Read `message`, which names the field or recipient |
| 400 | `CATEGORY_NOT_ALLOWED` (NS-1005) | OTP on a bulk send | Send OTPs one at a time |
| 401 | `UNAUTHORIZED` (NS-2001) | Missing or invalid API key, or your access is disabled | Check the key; contact the operator |
| 403 | `CHANNEL_NOT_ALLOWED` (NS-2008) | The channel isn't enabled for you | Ask the operator |
| 422 | `SENDER_NOT_VERIFIED` (NS-3007) | `from` isn't a verified sender | Verify it under Sender addresses |
| 402 | `INSUFFICIENT_CREDIT` (NS-4001) | Prepaid credit doesn't cover the request | Top up with the operator |
| 402 | `SPEND_CAP_EXCEEDED` (NS-4002) | The month's spend cap is reached | Ask the operator to raise it |
| 403 | `ACCOUNT_SUSPENDED` (NS-4003) | Billing is suspended | Contact the operator |
| 429 | `RATE_LIMITED` (NS-5001) | Too many calls | Wait the seconds in the `Retry-After` header, then retry |
| 5xx | `INTERNAL_ERROR` (NS-9001) and others | A fault on the platform's side | Retry with the same `Idempotency-Key`; quote the `traceId` if it persists |

### What a failure means

A message that ends `FAILED` carries an `errorCode`:

| Code | Meaning | What to do |
|---|---|---|
| `DELIVERY_REJECTED` (NS-6001) | The provider refused it, typically an invalid or blocked recipient. Its own words are in `lastError` | Correct the recipient and send again |
| `TEMPLATE_VARIABLE_MISSING` (NS-6002) | A value the text needs wasn't given | Send again with the value |
| `DELIVERY_ATTEMPTS_EXHAUSTED` (NS-6004) | Every attempt failed for a temporary reason, such as a provider outage | Usually resolved by the operator, who can resend it; or send it again later |
| `DELIVERY_DEAD_LETTERED` (NS-6005) | The platform gave up handing it to a worker | The operator is alerted and can resend it |
| `OTP_EXPIRED` (NS-6007) | A one-time password couldn't be sent within its validity | Let your user request a new code |

**While retrying,** a message is `RETRYING` with `PROVIDER_TEMPORARILY_FAILING` (NS-6006). You don't need to do anything.

**Resending failures:** download the failed recipients as CSV, correct them, and upload them as a new bulk request.

### Recipient activity report

The same report as the console's, as CSV:

```bash
curl -X POST "$CLIENT_API/v1/privacy/recipient-report" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"recipient":"ann@example.com","from":"2026-01-01","to":"2026-09-30"}' -o recipient-activity.csv
```

- **Dates:** `from` and `to` are optional, inclusive UTC dates of acceptance.
- **Why POST:** the address goes in the body, never in the URL, so it doesn't end up in access logs.
- **Columns:** `message_id`, `request_id`, `client_reference`, `channel`, `category`, `template`, `sender`,
  `recipient`, `current_status`, `event`, `occurred_at`, `attempt`, `error_code`, `error_id`, `provider_message_id`,
  `detail`, `source`.

### Limits

| Limit | Default | Notes |
|---|---|---|
| API calls | 50 per second, burst 100 | Your operator may have set yours differently. Over it: `429` with `Retry-After` |
| Delivery rate per channel | Set by your operator | Over it, messages wait their turn; nothing is refused |
| Recipients per bulk request | 50,000 | Split larger lists |
| CSV upload size | 20 MB | |
| Your own templates | 200 | |
| OTP validity | 60–900 seconds | Default 300 |

### A minimal integration checklist

1. Keep the API key in your secrets store, and send it as `X-API-Key`. If you have a signing secret, sign every
   request that changes something, with a new nonce each time.
2. Create your templates, with the OTP category for codes.
3. Send with an `Idempotency-Key` and your own `clientReference`.
4. On `202`, store the `requestId`, then poll it until it is no longer `PROCESSING`.
5. Branch on the error `code`. Retry only when `retryable` is true, waiting `Retry-After` on a `429`.
6. Log `errorId` and `traceId` with every failure, so support can find it.
7. Handle a rare duplicate message gracefully.
