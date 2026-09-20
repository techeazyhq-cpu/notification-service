# ADR-007: Client-owned, verified e-mail sender addresses

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

E-mail always left from the single `from` address of the SMTP provider an administrator configured. Clients asked to send from their own address (for example `orders@acme.com`), so that recipients recognise the sender and replies reach the client.

Letting a client put any address in the From header would let it impersonate other people, and would get the platform's mail servers blocked. The address therefore has to be proven before use.

## Decision

1. **Registered, then confirmed.** A client registers an address (`POST /v1/senders`, or the **Sender addresses** page in the client UI). The platform e-mails a link to that address; opening it marks the address `VERIFIED`. Only a verified address can be used. The link carries a random 256-bit token that works once and expires after 24 hours; only its SHA-256 hash is stored. The confirmation page needs no API key (the token is the credential) and answers 400 for a used, expired or unknown link.
2. **Choosing the sender.** A request may carry `from` (EMAIL only). If it names a verified address of the caller, that address is used; if it names anything else the request is refused with `422 SENDER_NOT_VERIFIED`; `from` on another channel is `400`. Without `from`, the client's default address is used if it has one, otherwise the provider's own address as before. A client can have one default, chosen among its verified addresses.
3. **Snapshot.** The chosen address and display name are copied onto the request (`sender_email`, `sender_name`) when it is accepted, like the content. Removing or changing a sender never alters requests already accepted, and the dispatcher needs no lookup.
4. **Delivery.** The SMTP provider sets `From: "Name" <address>` when the request carries a sender and uses its configured address otherwise. The confirmation mail is sent by the platform from the first enabled SMTP e-mail provider, so the platform, not the client, vouches for it.
5. **Abuse limits.** At most 10 addresses per client (`client-api.max-senders-per-client`), a confirmation can be re-sent once a minute, the same address is unique per client ignoring case, and a client without the EMAIL channel cannot register addresses. If the confirmation mail cannot be sent the address is not kept.
6. Migration 006 adds `client_sender` and the two request columns, with a rollback.

## Options considered

- **Accept any address.** Simplest, but allows spoofing and damages the platform's reputation. Rejected.
- **Use the client's address only as Reply-To, keep the platform From.** Safe for deliverability and needs no verification, but is not what was asked for (recipients see the platform as sender). Could be offered as a fallback.
- **Verify whole domains with DNS records (SPF/DKIM) instead of single addresses.** The right approach for high-volume senders and the way SES or similar providers work, but needs DNS lookups and a provider integration. Listed as a follow-up.

## Consequences

Positive: clients send as themselves without an administrator; spoofing another person's address needs access to that mailbox; nothing changes for clients that do not use it.

Negative / accepted:
- **Deliverability is not guaranteed.** Confirming the mailbox does not authorise our SMTP servers to send for that domain. Unless the client's SPF/DKIM allows the platform (or the provider signs for it), receiving servers may reject or junk the mail. The UI says so; a domain-level check is the follow-up.
- The provider must accept an arbitrary From address; providers such as SES only accept addresses or domains verified with them.
- The confirmation link needs the client API to be reachable at `client-api.public-base-url` (`CLIENT_API_PUBLIC_BASE_URL`; the default is `http://localhost:8080`, so set it in real deployments).
- An address that later loses its owner (mailbox closed and reassigned) stays verified until removed.
- No address is shown to administrators yet.

## Follow-ups

Domain verification with SPF/DKIM guidance; Reply-To option; admin view and revocation of client senders; periodic re-verification.
