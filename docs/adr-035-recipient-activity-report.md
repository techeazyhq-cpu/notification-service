# ADR-035: A recipient activity report for answering complaints to a data protection authority

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

Most jurisdictions now have privacy laws (GDPR, DPDPA, and others) under which a person can complain to a
supervisory authority about messages they received. The organisation that sent them, our tenant (the controller),
must then show what it sent that person, when, why, and what happened to each message.

The platform could not answer that well:

- **No history.** A message kept only its creation time, last update, sent time, attempt count and last error. There
  was no record of each delivery attempt, a retry, a dead letter, an operator's resend or the erasure itself.
- **No efficient lookup by address.** Nothing indexed a recipient's address, so finding one person's messages meant
  scanning all of a tenant's messages.
- **Erasure ended the evidence.** After 90 days, or on an erasure request, the retention job (ADR-009) replaces the
  address with `[erased]`. A complaint filed after that could not be evidenced at all.

The owner decided:

- the report is for **tenants**, about their own recipients;
- it needs a **full event log** with a timestamp for each step;
- messages must stay findable **after erasure**, through a fingerprint;
- it reports **purpose metadata only**, never the message text.

## Decision

1. **An append-only event log, `message_event`.** It records what happens to a message after it is accepted:

   | Event | When |
   |---|---|
   | `ATTEMPT_FAILED` | A delivery attempt failed for a temporary reason; another is scheduled |
   | `SENT` | The provider accepted the message |
   | `FAILED` | The message will not be delivered; its error code says why |
   | `EXPIRED` | A one-time password ran out of validity before it could be sent |
   | `DEAD_LETTERED` | The broker gave up handing the message to a worker |
   | `REQUEUED` | The platform operator put a failed message back on the send path |
   | `DATA_ERASED` | The recipient's data was erased, by retention or on request |

   - **Atomic:** each event is written in the same transaction as the status change it records. The erasure
     statements insert their events in the same SQL statement, so the log and the message never disagree.
   - **Acceptance is not an event row.** It is the message's creation time. A bulk accept of 50,000 recipients
     therefore writes no extra rows, and accept latency (ADR-008) is unchanged.
   - **Append-only:** the table refuses updates, by trigger, like the admin audit log (ADR-019). Its rows are deleted
     only together with their message, when retention deletes finished messages (400 days by default).
   - **No personal data:**
     - no address and no content;
     - no provider wording, which can quote the recipient;
     - instead: the error code, the attempt number, the provider's message id, and fixed text such as "Next attempt
       in 5 s".
2. **A recipient fingerprint, `notification_message.recipient_fingerprint`.**
   - **What it is:** HMAC-SHA256 of the normalised address, set when the message is accepted. E-mail addresses are
     compared without case. Phone numbers are compared without spaces, dashes, dots or brackets.
   - **Erasure keeps it.** Erasure, by retention or on request, still removes the address, the template values and the
     error text, but the fingerprint stays, so the tenant finds the messages again by giving the same address. The
     fingerprint cannot be turned back into the address.
   - **Key:** derived from the personal-data key (`DATA_ENCRYPTION_KEY`) under its own label, so no new secret has to
     be deployed. The existing guard refuses the development default outside `local` (ADR-013).
   - **Lookup:** a partial index `(client_id, recipient_fingerprint, created_at)` serves it. This is the "recipient
     blind index" listed as a follow-up in ADR-017.
   - **Older messages:** messages accepted before this change are fingerprinted by a dispatcher job in batches,
     through a partial index that is empty once it is done. Messages already erased before this change cannot be
     fingerprinted; their address is gone.
3. **The template's name is kept on the request** (`notification_request.template_name`) when the request is accepted.
   The report shows the name the message was sent with, even if the template was renamed or deleted since.
4. **The report.**
   - **API:** `POST /v1/privacy/recipient-report` with `{recipient, from?, to?}` returns a CSV. It is a POST so the
     address travels in the body, never in a URL that access logs and proxies record. The tenant's API key scopes it
     to its own messages.
   - **Console:** the client console's **Privacy** page has a **Recipient activity report** form.
   - **Content:** one row per event of every message to the recipient, oldest first, with UTC timestamps:
     - **Purpose:** category, template, the tenant's `client_reference`, request id, channel, sender address.
     - **Outcome:** the event, attempt, error code and id, the provider's message id, the current status.
     - **Recipient:** the address, or `[erased]`.
     - **Source:** a message from before the log existed has no events; its outcome comes from the message record and
       is marked `source=RECORD`.
   - **Never the message text,** so no one-time password code can leak through a file that gets e-mailed around.
   - Free text is neutralised against spreadsheet formulas.
   - **Logging:** each report is logged with the tenant, the first 12 characters of the fingerprint and the counts,
     never the address.

## Options considered

- **Admin access too, across tenants:** useful when a complaint reaches the platform itself. The owner chose tenants
  only: the tenant is the controller answering the authority, and the platform its processor.
- **A summary from the existing columns:** no new writes on the delivery path, but no timeline of attempts, retries,
  dead letters or erasure, which is exactly what an authority asks about.
- **Findable only until erasure:** the most privacy-preserving choice, but complaints regularly arrive months later.
- **Including the message text:** stronger evidence of what the person saw, but it puts content and OTP codes into a
  file that travels by e-mail.
- **A separate fingerprint secret:** independent rotation, but one more secret in the Helm chart, the External Secrets
  manifests and the Terraform of three clouds. A derived key gives the same separation.
- **Recording acceptance as an event row:** completes the log, but doubles the rows a bulk accept writes.

## Consequences

Positive:

- A tenant can answer a complaint with timestamped evidence of every step, even after the recipient's data was erased.
- Every delivery outcome is now recorded atomically with its status change.
- Finding one recipient's messages is an index lookup.

Negative / accepted:

- **The fingerprint is pseudonymised personal data,** still personal data under GDPR. Erasure on request keeps it,
  and with it the event log, for the record's lifetime (400 days by default). That is defensible as evidence for
  legal claims (GDPR Art. 17(3)(e)), but tenants must say so in their privacy notices. The client console's Privacy
  page states it.
- **One more insert per delivery outcome,** in the same transaction as the status change.
- **Changing `DATA_ENCRYPTION_KEY` also changes the fingerprint key.** Messages fingerprinted before then become
  unfindable. Key rotation is not supported yet (an ADR-017 follow-up); when it is, fingerprints need a key id too,
  like the encrypted values.
- **Gaps in older data:**
  - Messages accepted before this change have no event log; their outcome is reported from the record.
  - Messages erased before this change have no fingerprint and cannot be found by address.
- **Granularity:** the log records attempts that end with an outcome. A message held back by rate limiting or a
  provider outage records nothing until it is attempted, so the gap between acceptance and the first attempt is
  visible but not explained.
