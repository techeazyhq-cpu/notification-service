# ADR-047: A prompt evaluation set for AI drafting

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR-041

## Context

AI drafting (ADR-041, ADR-044) works with any LLM provider and model, and its prompt is in `DraftPrompts`. Three kinds
of change can make drafts better or worse without any test noticing:

- an operator switches provider or model, for example to a cheaper or a local one;
- someone edits the system prompt or the correction prompt;
- someone tightens or loosens the channel rules.

The unit tests use a scripted model. They prove the service handles replies correctly, but they cannot tell whether
a real model writes good drafts. Until now the only evidence was a handful of drafts run by hand and noted in
ADR-044. ADR-041 left "a prompt evaluation set, so a model or prompt change can be measured before it ships" as a
follow-up.

## Decision

1. **An evaluation set of 26 briefs** in the test resources (`draft-evaluation-cases.json`).
   - Every channel has at least one brief in every category.
   - The briefs are realistic, written as a business would describe the message: purpose, tone and the variables
     the draft may use.
   - Each brief also lists the words the draft must contain to say what was asked, matched ignoring case. Where a
     placeholder is essential, it is listed too: `{{code}}` for every one-time password, the reset link for a
     password reset and the tracking link for a shipped order.
   - A unit test checks the set itself: unique ids, every channel and category covered, and briefs the service
     accepts.
2. **The same path as a real draft.** `DraftEvaluation` sends every brief through `TemplateDraftService`, with its
   channel rules and its one correction. The model is wrapped so that each brief's calls, tokens and time are
   counted. Nothing is stored and no rate limit or budget applies. Each brief gets one of these verdicts:

   | Verdict | Meaning |
   |---|---|
   | `PASSED` | Met the rules and the brief's words at the first attempt |
   | `PASSED_AFTER_CORRECTION` | The same, after the correction |
   | `FELL_SHORT` | Met the rules but left out a word or placeholder the brief needs |
   | `REJECTED` | Broke the channel's rules even after the correction |
   | `DECLINED` | The model declined the brief |
   | `FAILED` | The model was unreachable or failed |

   The report gives:
   - the pass rate;
   - the share that passed at the first attempt;
   - the tokens spent;
   - the results by channel;
   - every brief's outcome.
3. **Listed variables are measured, not required.** In the editor the variables are ones a draft *may* use, and the
   service only warns when one is left out. The evaluation follows the same contract: a variable left out is counted
   in the **variable coverage** metric, the share of listed variables the drafts used, but does not fail the brief.
   The first version did fail such briefs, and it scored `llama3.2:3b` at 42% for leaving out `{{firstName}}`, which
   the product allows. Placeholders a message cannot do without are required through the brief's words instead
   (point 1).
4. **Run on request, never with the build**, because it spends tokens or needs a running model.
   - `DraftEvaluationRun` is outside Surefire's test patterns, so it runs only when named: `-Dtest=DraftEvaluationRun`.
   - System properties `drafting.evaluation.*` choose the provider, base URL, model, API key, a subset of briefs and
     the minimum pass rate.
   - The default is a local Ollama with `llama3.2:3b`.
   - It prints a Markdown report, writes it to `template-drafting/target/drafting-evaluation/`, and fails below the
     minimum pass rate (default 90%).
   - The `DraftEvaluation` unit tests, with a scripted model, do run with the build.
5. **Recorded baselines**, in `docs/drafting-evaluation/`, one report per model, dated. A change to the model or prompt
   is compared with the latest baseline for the same model, and a new baseline is committed with the change.

## Baseline, 2026-10-25 (local Ollama)

| Model | Passed | First attempt | Variables used | Tokens |
|---|---|---|---|---|
| `llama3.2:3b` | 24 of 26 (92%) | 85% | 74% | 21,683 |
| `llama3.2:1b` | 9 of 26 (35%) | 23% | 58% | 38,410 |

- **`llama3.2:3b`.** It met the rules on every channel. It missed two brief words in e-mail: no "welcome" in the
  welcome e-mail and no "sign" in the sign-in code e-mail. The 3b results were the same on both runs.
- **`llama3.2:1b`.** Most of its drafts broke the rules even after the correction. It added subjects to SMS and
  WhatsApp, invented placeholders such as `{{optOutLink}}`, wrote malformed braces and left stand-ins such as
  `[Company Name]`. Its pass rate moved between 27% and 35% from run to run. This matches ADR-044's hand checks, and
  shows the evaluation separates a usable model from an unusable one.
- **Variable coverage of 74%.** The prompt lets a model leave out listed variables, and it often leaves out
  `{{firstName}}` or a promo code. Changing the prompt to ask for every listed variable is a candidate change, and
  this evaluation is how to judge it.

## Options considered

- **An LLM as judge** of tone and clarity. It captures quality the rules cannot, but it adds a second model, its cost
  and its own variance. Deterministic checks come first; a judge can be added on top.
- **Run in CI against a hosted model.** It would catch regressions automatically, but every build would spend tokens
  and depend on a third party's availability, and a pull request from a fork must not see the key. Running on request
  before a model or prompt change is enough for how rarely they change.
- **Exact expected drafts.** Models do not write the same text twice, so the checks are on properties: the rules,
  the essential words and placeholders, and the variables used.

## Consequences

Positive:
- A model or prompt change now comes with a number that can be compared with a baseline.
- Weak models are visible before an operator picks one.
- The variable coverage metric points to the next prompt improvement.

Negative / accepted:
- Results vary between runs, more for small models, so compare pass rates with a margin of a few briefs or run twice.
- Checking for words is a rough measure of meaning. A draft can contain "code" and still be a poor message, and a good
  draft can use a synonym. The words are kept to ones a message cannot do without.
- 26 briefs is a small sample. Briefs are added when a draft goes wrong in practice.
