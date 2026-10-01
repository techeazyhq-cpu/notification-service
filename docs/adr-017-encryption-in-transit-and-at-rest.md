# ADR-017: Encryption in transit and at rest

- **Status:** Accepted
- **Date:** 2026-09-26

## Context

ADR-016 put TLS on the public edge. Behind it, every hop between a service and its datastore was still plaintext
(PostgreSQL, Redis, Pulsar), and the personal data stored at rest was readable to anyone with a database dump or a
backup. This ADR records what is now encrypted, what is deliberately not, and why.

## Where personal data actually lives

| Store | Personal data | Notes |
|---|---|---|
| PostgreSQL `notification_message` | recipient address, template variables (names, one-time codes), error text | The only place recipients are stored. Erased after 90 days, deleted after 400 (ADR-009). |
| PostgreSQL `notification_request` | message subject and body (a snapshot of the template) | Template text, not per-recipient data, except for single sends. |
| Pulsar | message id only (ADR-001) | No recipient, no content: nothing personal in topics, retry topics, dead-letter topics or broker backups. |
| Redis | rate-limit token buckets | Keys are client ids and channels. No personal data. |
| Provider gateways (SMTP, HTTP) | the message itself | Outside this system; SMTP `starttls` is a per-provider setting. |

The design already minimises the footprint: encryption effort belongs on PostgreSQL, and in transit on every hop
that carries a query or a message id.

## Decision

### In transit

1. **Public edge:** TLS via Traefik (ADR-016), unchanged.
2. **Datastore hops:** `docker-compose.encrypted.yml` turns on TLS for PostgreSQL (`hostssl` only, so a plaintext
   connection is refused, `sslmode=verify-full` on the clients), Redis (TLS port only, plaintext port disabled) and
   Pulsar (TLS port only, plaintext service port disabled, hostname verification on). Certificates come from a
   private CA that a one-shot container (`deploy/tls/gen-certs.sh`) creates in a volume; the CA private key is deleted
   as soon as the three server certificates are issued. Each datastore's key is mounted only into that datastore
   (Compose volume sub-paths), and services receive only the CA certificate. For production, replace the files
   with certificates from your own PKI at the same paths.
3. **Client side:** the Pulsar client trusts a configured CA (`notification.pulsar.tls-trust-certs-file`) and verifies
   the hostname; JDBC uses `sslrootcert`; Redis uses a Spring SSL bundle. All are opt-in by configuration, so
   the default development stack is unchanged.

### At rest

1. **Already encrypted (ADR-005, ADR-013):** provider credentials (SMTP password, gateway auth header), two-factor
   secrets. Already hashed: API keys, admin passwords, session tokens, recovery codes.
2. **New: template variables** (`notification_message.variables`), which carry names and one-time codes, are encrypted
   with AES-256-GCM by a JPA attribute converter (`EncryptedVariablesConverter`), keyed by a dedicated
   `DATA_ENCRYPTION_KEY` (separate from the provider-secrets key, so compromising one does not open the other; it
   is refused at start-up if left at the shipped default outside the `local` profile). The column stays `jsonb`: the
   stored value is `{"_enc":"enc:<ciphertext>"}`. Rows written before this change are read unchanged and become
   encrypted when next written; no migration, no downtime.
3. **The recipient column is not application-encrypted, on purpose.** It is searched (status filters, dead-letter
   search with `LIKE`, erasure by address, CSV export). Encrypting it means a keyed blind index for equality, and
   gives up substring search, which is a product decision for the calling teams, not a hardening detail. Until that
   is taken, **the database volume must be encrypted at the storage layer** (LUKS/dm-crypt on the host, an encrypted
   cloud volume such as EBS/Persistent Disk/Managed Disk with a KMS key, or a managed PostgreSQL with storage
   encryption on) and so must its backups. This is a deployment requirement, listed in the production checklist in
   the analysis document, not something this repository can enforce from Compose.

## Options considered

- **pgcrypto / column encryption inside PostgreSQL.** The key would be sent to the database on every query and
  appear in statement logs; application-level encryption keeps the key out of the database entirely.
- **Encrypting the recipient with a deterministic scheme.** Equality lookups work, but frequency analysis and any
  `LIKE` search do not; rejected as a silent behaviour change.
- **mTLS to every datastore.** Stronger (the datastore authenticates the service, not just the reverse), at the
  price of a client certificate per service to issue and rotate. Passwords still authenticate the services today;
  mTLS is the next step if the network between services is not trusted.
- **Traefik to backend TLS.** The last plaintext hop is proxy to service inside the Docker network. Acceptable on a
  private bridge network, not on a shared one; a service-mesh or per-service certificate is the follow-up.

## Consequences

Positive: a stolen database dump or backup no longer yields one-time codes or provider credentials; a network tap
between a service and any datastore sees only ciphertext; the default stack still starts with one command.

Negative / accepted: two more keys to provision and back up (`DATA_ENCRYPTION_KEY`, and the CA and server
certificates); losing `DATA_ENCRYPTION_KEY` makes stored variables unreadable, which for in-flight messages means
they cannot be rendered and will fail (finished messages are unaffected); the recipient address remains readable to a
database administrator or a raw disk copy until storage encryption is in place; the cost of variable encryption is
one AES-GCM operation per message on write and per delivery on read, measured in `docs/benchmarks`.

## Follow-ups

Recipient blind index and encryption; mTLS to datastores; TLS from the proxy to the services; key rotation (a
`key id` in the stored envelope so old and new keys can coexist); a secrets manager for the three keys.
