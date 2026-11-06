# Change request: submission cap — our spec

Source: harness `/specs/submission-cap-cr/spec.md` (change request, released at tick 180, V: 2). Published examples: no.
Rule ids SC1–SC8 are used in `SubmissionCapTest` and `examples/submission-cap-cr.hurl`. This changes the 3-talk limit
from [Talk submissions](talks) and **replaces** co-speakers rule C9 ("co-speaking doesn't count"). Decisions the
request doesn't state come from `workflow/http-conventions.md`; otherwise **(our guess)**.

## What a speaker's cap counts now

A talk counts toward a speaker's cap of **3** when **both**:
- the speaker is on it as **main speaker or co-speaker**, and
- the talk is **still in the running**: its status is not `REJECTED` and not `WITHDRAWN` (so `SUBMITTED`, `ACCEPTED`,
  `WAITLISTED` count; see [Acceptance](acceptance)).

The count is live: rejecting or withdrawing a talk, or removing someone as co-speaker, frees a place at once.

## API

No new endpoints, no new fields. The two guarded operations:

| Method | Path | Guarded by the cap? |
|---|---|---|
| `POST` | `/talks` | yes: the new talk is `SUBMITTED`, so it counts |
| `PUT` | `/talks/{id}/co-speakers/{speakerId}` | yes, **only when the talk is in the running** |

## Rules

| Id | Rule (from the change request) | Our decision |
|---|---|---|
| SC1 | The 3 now includes talks a speaker co-speaks on, not only their own. | Cap counts main-speaker and co-speaker talks together, per the query above. |
| SC2 | Rejected or withdrawn talks no longer count, for main speaker or co-speakers. | Count excludes `REJECTED` and `WITHDRAWN`. |
| SC3 | A speaker at the limit can't submit a new talk. | `POST /talks` when the count is already ≥ 3: **409**, nothing stored (unchanged code path, new count). |
| SC4 | …and can't be added as co-speaker to a talk that's still in the running. | `PUT …/co-speakers/{id}` when the co-speaker's count is ≥ 3 **and the talk is in the running**: **409**, nothing added. |
| SC5 | Being added to a rejected or withdrawn talk is fine (it doesn't count). | If the talk is `REJECTED`/`WITHDRAWN`, no cap check; the add succeeds (still subject to the max-2-co-speakers rule). |
| SC6 | Taking someone off a talk frees their place; a freed place is usable straight away. | No code: the count reads live data, so a later submit/add sees the freed place. |
| SC7 | Popular speakers get submissions and co-speaker invitations piling in together; the limit must still hold. | Both operations lock the **counted speaker's** row (`SELECT … FOR UPDATE`) before counting, so concurrent submits and co-speaker adds for one speaker run one at a time. Proven by removing the lock (test fails). |
| SC8 | Nothing existing is removed or changed; over-limit speakers keep everything but get nothing new until below. | Only `POST /talks` and `PUT …/co-speakers` check the cap; nothing re-checks existing talks. A speaker's talk list still shows only talks where they're the main speaker (unchanged). |

## Order of checks

`POST /talks`: (unchanged validation) → lock the speaker row → count in-the-running talks (main or co) → < 3 → insert
`SUBMITTED` → 201; else **409**.

`PUT /talks/{id}/co-speakers/{speakerId}`: talk exists (404) → co-speaker exists, locking the row (404) →
main ≠ co (400) → already a co-speaker? then 200, nothing changes → talk full, 2 co-speakers (409) →
talk in the running **and** co-speaker's count ≥ 3 (409) → add → 200.

## Interactions / guesses

- When the talk is `REJECTED`/`WITHDRAWN`, a co-speaker over the cap can still be added (SC5); the talk-full (max 2)
  rule still applies first. **(our guess on ordering: talk-full 409 before cap 409; both are 409, nothing changes.)**
- Re-adding an existing co-speaker is still "changes nothing" (200), with no cap check — they're already counted.
- The lock order is talk row → speaker row for co-speaker add, and speaker row only for submit; nothing locks a
  speaker then a talk, so no deadlock.

## What the harness tried (filled in after deploying, before claiming)

`python3 -I workflow/scripts/harness-traffic.py '/talks$|/co-speakers/' --all` after the deploy.
