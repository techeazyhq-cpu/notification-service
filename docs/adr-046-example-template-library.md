# ADR-046: A library of example templates that needs no model

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR-041

## Context

ADR-041 considered a library of example templates as an alternative to AI drafting and chose drafting, because a
library "does not adapt to a business's own purpose and variables". It kept the library as a follow-up, and it is
still worth having:

- **AI drafting is often unavailable.** It is optional and off by default. An operator may never configure a provider,
  and since ADR-045 a client can be switched off or have used up its budget. Until now, a client in that position
  started every template from an empty form.
- **Most messages are common ones.** One-time codes, order updates, appointment reminders, invoices and offers make up
  most of the traffic. A good example covers them at no cost and gives the same result every time.
- **The rules are easy to get wrong.** Authors break them without knowing: an SMS that runs to a second segment, a
  WhatsApp text ending in a placeholder, a promotional message without an opt-out, or a link typed into the text. An
  example shows a version that already meets the rules.

## Decision

1. **A curated catalogue in the `template-drafting` module**, in the file `template-examples.json`, which ships with
   admin-api and client-api.
   - It has 26 examples.
   - Every channel (SMS, e-mail, WhatsApp, push) has at least one example in every category (one-time password,
     transactional, promotional).
   - Each example has a stable id (`sms-otp-verification`), a short name, a one-line description, the subject or push
     title where the channel has one, and the body.
   - The placeholders are derived from the text.
   - The examples are plain text. Promotional SMS and WhatsApp examples include an opt-out, and promotional e-mails an
     `{{unsubscribeUrl}}`.
   - Business details such as the business name, links and codes are always placeholders, never invented values.
2. **Held to the drafting rules, strictly.** When the catalogue is loaded, every example is checked with the same
   `ChannelRules` an AI draft is checked with. Two things are refused:
   - an example with a violation, or even a warning;
   - an id used twice.

   A bad example therefore fails the tests and the service's startup, and never reaches an author. A test also checks
   that every channel has an example in every category.
3. **API.** Neither endpoint needs a model, a budget or a rate limit, and both work whether drafting is on or off.
   - `GET /v1/templates/examples?channel=&category=` for clients. It lists only the channels the client may use,
     because examples for other channels would be of no use to it.
   - `GET /api/admin/templates/examples` for administrators. Any administrator may read it, because listing examples
     spends nothing. Saving a template still needs `ADMIN`.
4. **UI.** A **Start from an example** panel sits in the template editor of both the client and the admin UI, above
   **Draft with AI**.
   - It offers the examples for the editor's channel and category.
   - **Use this example** fills the subject and body. It asks first if the editor already has text.
   - Nothing is saved until the author saves the template, as with a draft.
   - The client API playground lists the endpoint.

## Options considered

- **Examples as shared templates in the database.** Administrators could edit them in the console. But shared
  templates can be sent with directly, so an example would become a live template. Seeding data per environment would
  also need a migration for every new example. Examples belong to the release, like the channel rules they must meet.
- **Generate the examples with the drafting model.** They would vary from release to release and cost tokens to
  produce, which is what this library exists to avoid.
- **Localised examples.** Useful later. The catalogue format leaves room for a language field, but every example is in
  English for now, like the rest of the UI. Done in ADR-052: Hindi, Tamil and Arabic.

## Consequences

Positive:
- Every author has a good starting point on every channel, at no cost, whether drafting is on or off.
- The examples show what the channel rules expect.
- Examples cannot go stale against the rules, because a change to the rules that breaks one fails the build.

Negative / accepted:
- Examples do not adapt to a business's own wording or variables. An author edits them, or uses AI drafting when it
  is available.
- Adding or changing an example needs a release.
- The catalogue is in English only. ADR-052 adds Hindi, Tamil and Arabic.
