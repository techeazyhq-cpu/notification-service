# ADR-034: Each tenant may pay a price of its own for one-time passwords

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

ADR-033 gave one-time passwords (OTPs) a priority lane, a reserve in every rate-limit bucket, and an expiry. They
cost the platform more to carry than ordinary messages, yet billing (ADR-004) priced every message on a channel the
same: a plan holds one unit price per channel, and usage was counted per channel only. ADR-033 left "per-category
pricing" as a follow-up, and noted that pricing would also remove the incentive to label ordinary traffic as OTP.

The owner decided:

- the OTP price is **configurable per tenant**, and will differ between tenants;
- it is set **per channel**, and a tenant without one keeps paying its plan's price;
- OTPs **share** the channel's monthly free allowance with ordinary messages, which use it first.

## Decision

1. **A tenant's OTP prices live on its billing account,** one optional price per channel, in a new table
   `billing_account_otp_price` (migration 014).
   - **Currency:** prices are in the currency of the tenant's plan. A tenant with OTP prices cannot move to a plan in
     another currency until they are removed.
   - **Setting them:** an admin sets the complete set with `PUT /api/admin/billing/accounts/{clientId}/otp-prices`;
     viewers and operators may only read it. The admin console's **Billing accounts** page edits them, and the change
     is audited like any other (ADR-019).
   - **Seeing them:** tenants see their prices in `GET /v1/billing/account`, as `otpUnitPrice` next to each channel's
     price, and on the client console's billing page.
2. **Prepaid:** an OTP request, or an admin retry of an OTP, reserves credit at the tenant's OTP price for the channel.
   The hold records that price (ADR-004 §5), so settlement charges it even if the price changes meanwhile.
3. **Postpaid:** usage is counted per channel with OTPs apart from the rest.
   - Where the tenant has an OTP price, the invoice, the usage statement and the spend-cap projection show OTPs on a
     line of their own (`OTP_USAGE`, e.g. "SMS one-time passwords: 3000 sent, 0 included free").
   - **Shared allowance:** the channel's free allowance covers ordinary messages first; what is left covers OTPs.
   - Where the tenant has no OTP price for the channel, OTPs are ordinary messages of the channel, and the invoice is
     exactly what it was before this change.
4. **Usage stays an index-only read.** It is read at every postpaid accept with a spend cap. The partial index it uses
   is rebuilt to carry the category (`ix_message_sent_usage_category … INCLUDE (category)`, migration 015). The index
   is built concurrently and checked for validity, following the rules in ADR-003, and a test pins the index-only plan.

## Options considered

- **A surcharge percentage per tenant:** one number, and it follows plan price changes. But it cannot price channels
  differently, and an SMS OTP and an e-mail OTP differ greatly in cost.
- **OTP prices on the plan:** fewer concepts, but a tenant with a negotiated OTP price would need a plan of its own.
- **OTPs outside the free allowance:** a simpler invoice, but the owner chose to let OTPs use what ordinary traffic
  leaves.

## Consequences

Positive:

- OTP pricing is per tenant and per channel.
- Nothing changes for a tenant until a price is set.
- Prepaid and postpaid charge the same price, through the same calculator.
- Clients see what they pay.

Negative / accepted:

- **Price changes take effect on the next request.** A changed OTP price applies to OTPs accepted afterwards. For
  postpaid it also applies to any month whose invoice is still a draft, as plan edits already do (ADR-004).
- **Admission caches prices.** It caches OTP prices like accounts and plans, so a change can take up to
  `billing.admission-cache-seconds` (5 s) to reach the accept path.
- **Mixed versions during an upgrade.** An instance of the previous version cannot read an invoice that has an
  `OTP_USAGE` line. Upgrade the admin and client APIs together before setting any OTP price.
- **The category still is not verified (ADR-033).** A tenant whose OTP price is lower than its plan price would gain
  by labelling ordinary traffic as OTP. Set OTP prices at or above the plan price.
