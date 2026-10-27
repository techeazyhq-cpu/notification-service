# ADR-052: Example templates in Hindi, Tamil and Arabic

- **Status:** Accepted
- **Date:** 2026-10-28
- **Amends:** ADR-046

## Context

ADR-046 shipped 26 example templates, every one in English, and left localised examples for later. Many of the
businesses this service is meant for message their customers in other languages, in India and in the Middle East
especially. An author who wants a Hindi verification code still starts from a blank form, or spends tokens on AI
drafting.

Other scripts change one rule. Any character outside GSM-7, such as Devanagari, Tamil or Arabic, makes the whole SMS
UCS-2. A segment then holds 70 characters instead of 160, so a translated SMS is usually longer in segments than its
English original. The channel rules warn about both: the encoding and a message of more than one segment.

## Decision

1. **Hindi (`hi`), Tamil (`ta`) and Arabic (`ar`)**, each in a file of its own next to the English catalogue:
   `template-examples-hi.json`, `-ta.json` and `-ar.json`. A reviewer for one language touches only that file.
2. **A translation translates an English example, by id.**
   - It gives only the subject and body.
   - It keeps the English example's channel, category, name and description. The console is in English, so the name
     and description stay in the author's language too.
   - Its id is the English id plus the language, as in `sms-otp-verification-hi`, and every example has a
     `language` field.
3. **Checked when the catalogue loads**, so a bad translation fails the build and startup, as in ADR-046:
   - it must translate an English example that exists, and only once;
   - it must use exactly the same placeholders, so a translation cannot drop `{{code}}` or invent `{{name}}`;
   - it must meet every channel rule. The only warnings allowed are the two the script brings to an SMS: the encoding
     and more than one segment. The three-segment limit still applies.
   - A test also keeps every translated one-time-password SMS to a single segment, since those are sent most.
4. **Keywords stay in Latin letters.** `STOP`, `YES`, `NO` and `C` are left untranslated, because carriers and
   reply handlers match them as written.
5. **The API and the consoles.**
   - `GET /v1/templates/examples` and `/api/admin/templates/examples` take an optional `language` parameter. Without
     it, every language is listed.
   - The example picker in both consoles offers a language whenever an example has translations. It keeps the same
     example chosen when the language changes.
   - The subject and body fields use `dir="auto"`, so Arabic text is written and shown right to left.
6. **Shipped as drafts for review.** The translations were written without a native speaker's review. The picker
   says so under a translated example, and they should be reviewed before they are presented as finished.

## Options considered

- **Translate the name and description too.** The console around them is English. Translated labels would make the
  picker harder to read for the author, who is not necessarily the reader of the message.
- **One catalogue file with every language.** Fewer files, but every reviewer would edit the same file, and a
  language would be harder to add or remove.
- **Let translations differ from the English placeholders.** Some languages might read better with a different
  sentence, but an example's placeholders are its contract with the sender. A translation that drops one breaks a
  send that worked in English.
- **Translate with the drafting model at runtime.** It costs tokens on every use and varies between runs, which is
  what the library exists to avoid (ADR-046).

## Consequences

- Authors writing in Hindi, Tamil or Arabic start from a correct example on every channel, at no cost.
- A translated SMS is mostly two segments where the English one is one; the length shown in the editor says so.
  One-time-password texts are shorter than their English originals in order to fit one segment.
- 78 more examples to maintain. A change to an English example's placeholders fails the build until each translation
  follows.
- The wording needs a native speaker's review in each language.
