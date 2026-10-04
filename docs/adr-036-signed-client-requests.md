# ADR-036: Signed client API requests against replay

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

The client API authenticates a caller with a static API key in `X-API-Key`. The key proves who is calling, but
nothing ties a request to a moment or to a single use. Anyone who obtains a complete request can send it again as
often as they like, and every copy sends the notifications again and is billed again. A request might be obtained
from:

- the client's own logs or an error report;
- a debugging proxy;
- a compromised host on the client's side;
- a TLS-terminating middlebox.

The defences we already had each cover only part of this:

- **TLS everywhere** (ADR-016, ADR-017) stops anyone on the network from reading a request. It does nothing once a
  request has been captured at an end point.
- **`Idempotency-Key`** turns a repeated submission into a no-op that returns the original request. But the header is
  optional, the attacker can change it, and keys are forgotten after 7 days (ADR-009).
- **Per-client rate limits** cap how fast replays can be sent, not whether they work.

The rest of the platform already resists replay. An administrator's two-factor code is accepted once per time step,
admin sessions expire, an expired one-time password is never delivered late, and there are no inbound webhooks. The
client API is the remaining gap.

Some tenants (banks, healthcare, public sector) have audit requirements that call for message-level integrity and
replay protection. Others integrate from simple scripts and must not be forced into signing.

## Decision

1. **Opt-in HMAC request signatures, per client.** An administrator issues a client a signing secret (`nss_` + 256
   random bits) in the admin console or with `POST /api/admin/clients/{id}/signing-secret`. It is shown once.
   - The service must recompute each signature, so it stores the secret itself rather than a hash. The secret is
     encrypted with AES-256-GCM under the credentials key (`notification.secrets-key`), the same key that protects
     provider credentials.
   - Issuing a new secret replaces the old one. Removing it (`DELETE …/signing-secret`) also turns off the requirement
     in 3.

2. **What is signed.** Three headers sign a request:
   - `X-Signature-Timestamp`: Unix seconds;
   - `X-Signature-Nonce`: 16–64 characters of `[A-Za-z0-9_-]`;
   - `X-Signature`: the lower-case hex HMAC-SHA256, keyed by the secret's UTF-8 bytes, over these seven lines joined
     by `\n`:

   ```text
   NS1-HMAC-SHA256
   {timestamp}
   {nonce}
   {METHOD}
   {path as sent}
   {query string as sent, without '?', or empty}
   {hex SHA-256 of the exact body bytes}
   ```

   This binds the signature to the method, the path, the query and the exact body bytes. Hashing the raw bytes, rather
   than a canonical form of the JSON, gives clients nothing to normalise: they sign exactly what they send. The
   `NS1-HMAC-SHA256` line names the scheme, so a future version can change it without ambiguity.

3. **When a signature is checked, and when one is required.**
   - A request that carries any signature header is always verified, so a client can start signing before it is
     required.
   - With *signatures required* (`PUT …/signing-required`), an unsigned `POST`, `PUT`, `PATCH` or `DELETE` is refused.
     Reads are not, because a replayed read changes nothing.
   - The requirement can only be switched on once the client has a secret.
   - The check runs in `ClientAuthFilter`, after the API key and before the rate limit, so a caller holding a leaked key but not the signing secret cannot spend the client's quota.

4. **Freshness.** The timestamp must be within ±300 seconds of the service's clock
   (`client-api.signature-window-seconds`). Outside that, the request is refused with `SIGNATURE_EXPIRED` (NS-2013).

5. **Single use.** Once the signature is valid, the nonce is claimed for that client until timestamp + window.
   - A second claim of a live nonce is refused with `REQUEST_REPLAYED` (409, NS-2014). Retries must use a new nonce,
     and resubmitting the same send stays safe through `Idempotency-Key`.
   - The nonce is claimed only after the signature checks out, so forged requests cannot fill the store or use up a
     legitimate client's nonces.

6. **The nonce store is PostgreSQL, not Redis.**
   - Table `api_request_nonce` has primary key `(client_id, nonce)`. A claim is a single
     `INSERT … ON CONFLICT DO UPDATE … WHERE expired` statement: atomic across every client-api instance, and an expired
     row that has not been purged yet never blocks a nonce.
   - The dispatcher deletes expired rows in batches every 10 minutes.

7. **Errors.**

   | Error | Status | Meaning |
   |---|---|---|
   | `SIGNATURE_REQUIRED` (NS-2011) | 401 | Signatures are required and the request is unsigned |
   | `SIGNATURE_INVALID` (NS-2012) | 401 | Missing or malformed headers, no secret, or the signature doesn't match |
   | `SIGNATURE_EXPIRED` (NS-2013) | 401 | The timestamp is outside the window |
   | `REQUEST_REPLAYED` (NS-2014) | 409 | The nonce was already used |

   A signed body is capped at the upload limit plus 1 MB (otherwise 413 `PAYLOAD_TOO_LARGE`).

8. **Multipart uploads are signed like everything else.** Tomcat parses a multipart body directly from the
   connection, so a filter cannot hash the body and then let it be parsed as usual. For a signed request,
   `CachedBodyRequest` therefore reads the body into memory, hashes it, then answers Spring's part and parameter calls
   by parsing the remembered bytes with Tomcat's own multipart parser. This ties that class to embedded Tomcat, the only
   container the services run on. Unsigned requests are untouched.

9. **The client console signs too.** At sign-in a tenant may also paste its signing secret. Like the API key, it is
   held only in the page's memory and never written to browser storage, so reloading the page signs out. Every write
   the console or its API playground makes is signed with Web Crypto. CSV uploads are built byte by byte, so the bytes
   signed are the bytes sent.

## Options considered

- **Make `Idempotency-Key` mandatory.** This is simple and stops accidental duplicates. But the attacker controls the
  header: a replay with a new key is a new request. It also breaks every existing integration.
  - Rejected as *the* defence; still recommended for retries.
- **Mutual TLS with client certificates.** This proves the caller's identity on every connection. But it does not stop
  replay by whoever holds the certificate. It needs certificate issuance and rotation for every tenant, and it does
  not pass through TLS-terminating proxies.
  - Rejected.
- **Short-lived OAuth 2.0 access tokens.** These shrink the window for reusing a stolen credential. But they do not
  bind a request's content and do not stop a replay while the token lives. They also need a token endpoint and client
  credential flows.
  - Rejected for now. They are complementary, and remain a candidate for a later decision.
- **HTTP Message Signatures (RFC 9421).** This is a standard with richer covered components. But its canonicalisation
  is considerably harder for tenants to implement in a shell script, and client libraries are uneven.
  - Not adopted now. The `NS1-HMAC-SHA256` scheme line leaves room to accept RFC 9421 later.
- **Nonces in Redis (`SET NX EX`).** This is fast and already deployed for rate limiting. But Redis may evict keys
  under memory pressure, and a lost nonce is a successful replay. Redis also fails open by design (ADR-032), the wrong
  trade-off for a security check. A request that passes this check writes to PostgreSQL anyway, so the extra insert
  costs little.
  - Rejected.
- **Signatures required for every method.** This is uniform. But a replayed read has no effect, and requiring
  signatures on reads would break the console's views for a tenant that only wants its writes protected.
  - Rejected.
- **The signing secret derived from the API key.** This gives clients nothing new to manage. But it couples rotation
  of the two, and the service would have to keep the API key in recoverable form instead of as a hash.
  - Rejected.

## Consequences

- **Protection.** A captured signed request can't be replayed, and can't be altered without the signature failing.
  For a client that requires signatures, a stolen API key alone can no longer send or change anything.
- **Cost per signed request.** One HMAC, one SHA-256 over the body, one AES-GCM decryption and one indexed insert.
  The body is held in memory: up to about 21 MB for the largest CSV upload.
- **Clock and nonces.** Clients need a synchronised clock (NTP) and a new nonce per request. Retries must be signed
  afresh; the client guide says so and gives code in shell, Python, Node.js and Java.
- **Rotation is immediate.** Issuing a new secret makes requests signed with the old one fail within the client API's
  30-second cache. To rotate without errors, the client stops signing, the administrator makes signing optional, then
  issues the new secret. Accepting the previous secret for a grace period is left for a later change.
- **Shared exposure in the console.** The console holds the signing secret in the same page as the API key, so
  script running in that page could use both. Signing in the console protects against replay of captured traffic, not
  against a compromised browser.
- **Container coupling.** Multipart verification depends on embedded Tomcat's parser classes. A move to another
  servlet container must replace `CachedBodyRequest`'s multipart parsing. `CachedBodyRequestTest` runs Spring's
  multipart resolver on top of it, so a Tomcat upgrade that breaks this fails the build.
- **Schema.** Migration 019 adds `client.signing_secret`, `client.signing_required` (default `false`) and the
  `api_request_nonce` table. Existing clients are unaffected until an administrator issues them a secret.
