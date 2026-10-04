# ADR-037: Versioned encryption at rest and key rotation

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

Three keys encrypt data at rest with AES-256-GCM (ADR-005, ADR-017, ADR-036):

| Key | Encrypts |
|---|---|
| `SECRETS_ENCRYPTION_KEY` | provider credentials in `provider_config.settings`, client signing secrets in `client.signing_secret` |
| `DATA_ENCRYPTION_KEY` | message template variables in `notification_message.variables`; also keys recipient fingerprints |
| `ADMIN_TWO_FACTOR_KEY` | administrators' authenticator secrets in `admin_user.totp_secret` |

Before this decision, all three used one format: base64 of the IV and ciphertext, keyed by the SHA-256 of the configured value. A review found four weaknesses:

- **No key id, so no rotation.** A ciphertext does not say which key wrote it. Changing a key made every stored value unreadable, so a leaked key could not be replaced without downtime and an offline re-encryption.
- **No associated data.** Nothing bound a ciphertext to what it is. Provider credentials and client signing secrets shared one key, so a value could be moved from one column to the other and still decrypt.
- **No strength check.** Only the shipped development default was refused (ADR-013). A short memorable passphrase was accepted, and SHA-256 does nothing to slow down guessing it.
- **Two copies of the code.** The admin API had its own copy of the cipher for two-factor secrets.

## Decision

1. **Versioned ciphertext with a key id.** New values are written as `v2.<key id>.<base64 of IV and ciphertext>`. The key id is the first 8 hex digits of HMAC-SHA256(configured key, `"notification/key-id"`), so it names the key without revealing it.
2. **One derived key per purpose, bound as associated data.** Each use has a purpose (`provider-settings`, `client-signing-secret`, `message-variables`, `admin-totp-secret`).
   - Its AES key is HMAC-SHA256(configured key, `"notification/aes-gcm/v2/" + purpose`).
   - The purpose is passed to GCM as associated data, so a value encrypted for one column fails authentication anywhere else, even under the same configured key.
   - Associated data is per column, not per row: the JPA converter for message variables has no row id. Swapping two values within the same column still needs write access to the database, which an attacker could use more directly anyway.
3. **Rotation with previous keys.** Each key gains a `…_PREVIOUS` setting (comma-separated). The current key encrypts, and the current or any previous key decrypts. Values in the earlier format are still read, trying the current key and then each previous one.
4. **Re-encryption job.** The admin API's `SecretReencryption` runs at start-up and then hourly (`admin.reencryption-interval-ms`). It rewrites client signing secrets, provider settings and two-factor secrets that are in the earlier format or under a previous key.
   - Provider secrets still stored in plaintext from before ADR-017 are encrypted too.
   - Each row is replaced only if it still holds what was read, so several instances, or an administrator's edit in between, never lose a change.
   - Message variables are not rewritten: they are erased after the personal-data retention period (ADR-009). A previous data key only has to stay configured that long.
5. **Fingerprints keep their own key.** Recipient fingerprints (ADR-035) and idempotency-payload fingerprints are keyed HMACs compared against stored values, so rotating their key would orphan every existing fingerprint. They now use `FINGERPRINT_KEY`, which defaults to `DATA_ENCRYPTION_KEY`, so a data-key rotation can pin it to the old value.
6. **Minimum strength.** Outside the `local` profile every encryption key must be at least 32 characters. The documented `openssl rand -hex 32` gives 64.
7. **One implementation.** `AesGcmCipher` in notification-core serves all four purposes, and the admin API's copy is removed.

## Rotating a key

For `SECRETS_ENCRYPTION_KEY` (the same steps apply to `ADMIN_TWO_FACTOR_KEY`):

1. Generate a new key (`openssl rand -hex 32`).
2. Deploy every service with the new key as `SECRETS_ENCRYPTION_KEY` and the old one in `SECRETS_ENCRYPTION_KEY_PREVIOUS`.
3. The admin API re-encrypts at start-up and logs `Re-encrypted under the current keys: …`. Wait for a run that rewrites nothing; after the next hourly run, the log line no longer appears.
4. Remove `SECRETS_ENCRYPTION_KEY_PREVIOUS` and redeploy. The old key can now be destroyed.

For `DATA_ENCRYPTION_KEY`:

- Before the new key, set `FINGERPRINT_KEY` to the current data key.
- Keep the old key in `DATA_ENCRYPTION_KEY_PREVIOUS` for the personal-data retention period (`RETENTION_PERSONAL_DATA_DAYS`, 90 by default) before removing it.

## Alternatives considered

- **A KMS or Vault with envelope encryption.** This is stronger, since the key never leaves the HSM. But it ties the service to one cloud's KMS, or adds Vault to every environment, including local. The key id in the format leaves room for it later: a `v3` can name a KMS key.
- **Per-row associated data.** It would also stop swaps within a column. It needs the row id at encryption time, which the JPA converter does not have, and it buys little against an attacker who can already write to the database.
- **A slow KDF (Argon2, scrypt) on the configured key.** That only matters for guessable passphrases. Requiring a long random key is simpler and keeps start-up fast.
- **Re-encrypting message variables too.** That would mean rewriting a large, hot table. Retention already removes them on a schedule.

## Consequences

Positive:

- Keys can be rotated with no downtime and no offline step.
- A ciphertext copied to another column no longer decrypts.
- Weak keys are refused at start-up.
- There is one cipher to review.
- Provider secrets left in plaintext by old releases get encrypted.

Negative / accepted:

- A deployment with a key shorter than 32 characters refuses to start after this change. Generate a new key and rotate as above, with the short key as the previous key.
- Ciphertexts are 12 characters longer, which fits every column.
- Rotating the data key needs the old key kept for the retention period.
- Associated data is per column, not per row.
