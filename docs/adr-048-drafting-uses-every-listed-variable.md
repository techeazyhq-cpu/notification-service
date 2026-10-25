# ADR-048: Drafts use every placeholder the brief lists

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR-041

## Context

The first run of the prompt evaluation (ADR-047) found that `llama3.2:3b` used only 74% of the placeholders the briefs
listed. It often left out `{{firstName}}`, an order number or a promo code. The prompt never asked for them:

- the system prompt said "Use only the placeholder names the brief lists", which limits the names the model may use
  but does not require any of them;
- the brief said "Placeholders you may use".

A business lists a placeholder because the message needs that value. A draft that leaves it out has to be fixed by hand
in the editor, which only shows a "Does not use" warning.

## Decision

1. **The system prompt asks for every listed placeholder:** "Use every placeholder the brief lists, each at least once,
   where it reads naturally … Use no placeholder the brief does not list." The brief line becomes "Placeholders to use,
   each at least once: …", or "none besides {{recipient}}, if needed" when no placeholders are listed.
2. **The correction keeps them.** When a draft breaks a rule and is sent back, the correction prompt now also lists
   the placeholders the draft left out ("Does not use {{orderId}}, which the brief lists"). Fixing a rule therefore
   does not lose a variable.
3. **No extra call for a missing variable alone.** A draft that meets the rules but leaves a placeholder out is still
   returned with the "Does not use" warning, as before. That still happens, though less often. Sending it back would
   cost a second call each time for a gain the author can make by hand. The listed variables stay ones the draft
   *may* use for scoring (ADR-047), and the prompt now asks for all of them.

## Measured with the evaluation

The same 26 briefs on local Ollama, compared with the ADR-047 baseline:

| Model and prompt | Passed | First attempt | Variables used | Tokens |
|---|---|---|---|---|
| `llama3.2:3b`, before | 24 of 26 (92%) | 85% | 74% | 21,683 |
| `llama3.2:3b`, after, run A | 24 of 26 (92%) | 77% | 93% | 25,718 |
| `llama3.2:3b`, after, run B | 24 of 26 (92%) | 85% | 86% | 23,966 |
| `llama3.2:1b`, before | 9 of 26 (35%) | 23% | 58% | 38,410 |
| `llama3.2:1b`, after | 10 of 26 (38%) | 12% | 96% | 43,616 |

- **Coverage.** For `llama3.2:3b`, the share of listed variables used rose from 74% to 86–93%.
- **Pass rate.** It stayed the same.
- **First attempt.** The share that passed at the first attempt was 77% and 85% in the two runs. That is a spread of
  two briefs, and the baseline's 85% falls within it.
- **Cost.** Tokens rose 11–19%. The prompt is slightly longer, and run A needed two more corrections.
- **`llama3.2:1b`.** It now uses almost every variable, but it still breaks the rules too often to be usable.

The reports are in `docs/drafting-evaluation/` (`*-every-variable*`).

## Consequences

Positive:
- Drafts carry the values the business asked for, so authors edit them less.

Negative / accepted:
- About 10–20% more tokens per draft on a small local model.
- With a longer list of placeholders, a model may fit them in less gracefully. The evaluation's words and channel
  rules still apply, and the author reviews every draft.
