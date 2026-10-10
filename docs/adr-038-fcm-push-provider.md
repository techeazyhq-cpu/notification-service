# ADR-038: Firebase Cloud Messaging as the push provider

- **Status:** Accepted
- **Date:** 2026-10-10

## Context

`PUSH` was a channel without a real provider. Only `HttpJsonProvider` could be configured for it, and no push service
accepts that generic JSON, so push reached the local catcher and nothing else. The first client that needs real push is
a consent manager notifying people in their browser when an organisation asks for their consent. Web browsers
(Chrome, Firefox, Edge, Safari) and Android and iOS apps can all receive push through Firebase Cloud Messaging (FCM),
addressed by a device token the client app obtains from the Firebase SDK.

## Decision

1. **A provider type `FCM`, implemented by `FcmProvider`, sends through the FCM HTTP v1 API**
   (`POST /v1/projects/{projectId}/messages:send`). The recipient is the device token; the subject becomes the
   notification title, the body its text, and the message id goes in `data.messageId`. An optional `link` setting
   is the https page a web notification opens when clicked.
2. **It authenticates as a Google service account, with no new dependency.**
   - Settings `projectId`, `clientEmail` and `privateKey` come from a service account key with the *Firebase Cloud
     Messaging API Admin* role. `privateKey` is the PKCS #8 PEM as the key file holds it.
   - The provider signs a JWT (RS256, JDK only) and exchanges it at Google's token endpoint for a one-hour access
     token, which it caches until a minute before expiry. A 401 from FCM drops the cached token.
   - `privateKey` is a secret setting: encrypted at rest and masked in the admin API like `password` and `authHeader`
     (ADR-017, ADR-037).
3. **Failures are classified for the circuit breaker (ADR-032).**
   - Permanent, so the message fails with `DELIVERY_REJECTED` and is not retried: the token is no longer registered
     (`UNREGISTERED`, 404), it is malformed (`INVALID_ARGUMENT`), or it belongs to another Firebase project
     (`SENDER_ID_MISMATCH`).
   - Transient, so the message is retried and the provider's breaker counts it: throttling (429), FCM outages (5xx),
     timeouts, a refused or failed token exchange, and a 401 or 403 for the service account itself. A broken key is a
     configuration problem, not the recipient's.
   - The device token is never put in an error message.
4. **Both endpoints are provider destinations (ADR-022).** `fcm.googleapis.com` and `oauth2.googleapis.com` are the
   defaults, overridable with the `url` and `tokenUrl` settings; both are checked on save and before every send. A
   deployment with `PROVIDER_OTHER_PUBLIC_HOSTS_ALLOWED=false` lists them in `PROVIDER_TRUSTED_HOSTS`.

## Consequences

- An operator turns on real push by adding an `FCM` provider for the `PUSH` channel; clients keep sending to
  `PUSH` with the device token as the recipient. The catcher provider can stay configured at a lower priority.
- A client must clean up device tokens itself: FCM reports a dead token only as a failed message
  (`DELIVERY_REJECTED`, `lastError` containing `UNREGISTERED`). Delivery receipts remain out of scope (design §9).
- APNs direct (without Firebase) and per-platform options (Android channel, iOS sound and badge) are not covered;
  they can be added as settings, or as another provider type, when a client needs them.
