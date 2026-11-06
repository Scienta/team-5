# Acceptance state machine: our spec

Source: harness `/specs/acceptance/spec.md` (Version 1, drip feature released at tick 20, V: 2). No published
examples. Rule ids A1–A12 are used in `AcceptanceTest` and `examples/acceptance.hurl`. Decisions the request doesn't
state are marked **(our guess)**; see `.claude/rules/spec-decisions.md`.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| `PUT` | `/talks/{id}/status` | `{"status": "ACCEPTED" \| "REJECTED" \| "WAITLISTED"}` | 200, the Talk |
| `POST` | `/talks/{id}/withdraw` | `{"speakerId": <integer>}` | 200, the Talk |

`Talk.status` takes any of `SUBMITTED`, `ACCEPTED`, `REJECTED`, `WAITLISTED`, `WITHDRAWN`, wherever a Talk is shown.

## States and transitions

| From \ to | ACCEPTED | REJECTED | WAITLISTED | WITHDRAWN |
|---|---|---|---|---|
| SUBMITTED | ✓ (≥ 2 reviewers) | ✓ | ✓ | ✓ main speaker |
| WAITLISTED | ✓ (≥ 2 reviewers) | ✓ | 409 **(our guess)** | ✓ main speaker |
| ACCEPTED | 409 (final) | 409 (final) | 409 (final) | ✓ main speaker |
| REJECTED | 409 (final) | 409 (final) | 409 (final) | 409 |
| WITHDRAWN | 409 | 409 | 409 | 409 |

## Rules

| Id | Rule (from the feature request) | Our decision |
|---|---|---|
| A1 | The committee accepts, rejects or waitlists a talk. | `PUT /talks/{id}/status`, 200 with the Talk in its new status. |
| A2 | A waitlisted talk can still be accepted or rejected later. | WAITLISTED → ACCEPTED / REJECTED allowed. |
| A3 | Once accepted or rejected, the committee's decision is final. | Any decision on an ACCEPTED or REJECTED talk: **409**, nothing changes. Also the same decision again (ACCEPTED → ACCEPTED) **(our guess: not allowed = error, not "changes nothing" 200)**. |
| A4 | Only accept a talk that at least two different reviewers have rated; a reviewer who rated twice is one reviewer. | Fewer than 2 distinct reviewers: **409** **(our guess: a state conflict, not bad input)**. Counted from `rating` (one row per reviewer per talk). |
| A5 | Rejecting and waitlisting need no ratings. | No rating check for REJECTED / WAITLISTED. |
| A6 | A speaker can withdraw their own talk, unless it was already rejected or withdrawn. | `POST /talks/{id}/withdraw` from SUBMITTED, WAITLISTED or ACCEPTED: 200, `WITHDRAWN`. From REJECTED or WITHDRAWN: **409**. |
| A7 | Nobody else can withdraw it. | `speakerId` isn't the talk's main speaker (including a co-speaker, or an unknown id): **403** **(our guess: co-speakers don't own the talk; 403 = forbidden for this speaker)**. |
| A8 | A withdrawn talk is out for good. | Every decision and withdrawal on it: 409. |
| A9 | The current status shows wherever the talk is shown. | `status` on every Talk response (it already comes from the `talk` row). |
| A10 | A decision or withdrawal that isn't allowed changes nothing. | Every error leaves the talk exactly as it was. |
| A11 | Several committee members click at once; the talk must never end in a state the rules don't allow. | Every decision and withdrawal locks the talk row (`SELECT … FOR UPDATE`), checks the rules on the locked row, then updates. Concurrent ACCEPT + REJECT on a SUBMITTED talk: exactly one wins, the other gets 409. |
| A12 | (Not in the request) Bad input. | Talk not found: **404**. Body missing, not JSON, `status` missing or not one of the three decisions (incl. `SUBMITTED`, `WITHDRAWN`, lower case): **400**. `speakerId` missing or not an integer: **400**. |

## Order of checks

`PUT /talks/{id}/status`: talk exists (404) → body valid (400) → lock the talk → transition allowed from its current
status (409) → for ACCEPTED, ≥ 2 distinct reviewers (409) → update → 200.

`POST /talks/{id}/withdraw`: talk exists (404) → body valid (400) → lock the talk → `speakerId` is the main speaker
(403) → status allows withdrawal (409) → update → 200.

## Not changed by this feature (our guess)

- The limit of 3 talks still counts every talk of a speaker, whatever its status (also WITHDRAWN and REJECTED).
- Rating, listing, ranking and co-speakers work the same for talks in any status.

## Questions for the product owner (before claiming)

1. WAITLISTED → WAITLISTED, and ACCEPTED → ACCEPTED: an error (409, our guess) or "nothing changes" (200)?
2. Can a co-speaker withdraw the talk, or only the main speaker (our guess)?
3. Wrong speaker withdrawing: 403 (our guess), or another code?
4. Does a withdrawn or rejected talk still count towards the speaker's 3 submissions (our guess: yes)?
5. Accepting with too few ratings: 409 (our guess) or 400?
