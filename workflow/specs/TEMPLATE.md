# <Feature name>: our spec

Source: harness `/specs/<slug>/spec.md` (Version N, released at tick T, V: n). Published examples: yes / no.
Rule ids X1–Xn are used in `<Feature>Test` and `examples/<slug>.hurl`. Decisions the request doesn't state are
marked **(our guess)**; see `.claude/rules/spec-decisions.md`.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| | | | |

What the responses look like, and which existing responses gain fields.

## Rules

One row per sentence of the feature request, then one per decision it leaves open.

| Id | Rule (from the feature request) | Our decision |
|---|---|---|
| X1 | <quote or close paraphrase> | <behaviour and status code; **(our guess)** where the request doesn't say> |
| Xn | (Not in the request) Bad input: unknown ids, missing or malformed body. | 404 / 400 … |
| Xn | (Not in the request, but implied by "at once", "rush", "several people") Concurrency. | Which row is locked, and what the test proves. |

## Order of checks

Path resources (404) → body (400) → state (409) → change → success. One numbered list per endpoint.

## Not changed by this feature

What other features keep doing, and our guesses about interactions (limits, lists, statuses).

## Questions for the product owner (before claiming)

Every **(our guess)** that changes behaviour, not just a status code, first.

## What the harness tried (filled in after deploying, before claiming)

`python3 -I workflow/scripts/harness-traffic.py '<path regex>'`: the sequences its checks send and our answers.
