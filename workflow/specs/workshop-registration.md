# Workshop registration with capacity: our spec

Source: harness `/specs/workshop-registration/spec.md` (Version 1, drip feature released at tick 67, V: 2). No
published examples. Rule ids W1–W11 are used in `WorkshopRegistrationTest` and `examples/workshop-registration.hurl`.
Decisions the request doesn't state are marked **(our guess)**; see `.claude/rules/spec-decisions.md`.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| `POST` | `/workshops/{id}/registrations` | `{"name": "...", "email": "..."}` | 201, the Registration |
| `GET` | `/workshops/{id}/registrations` | — | 200, the Registrations in the order they registered |

`{id}` is the workshop's talk id. A Registration is `{"id", "workshopId", "name", "email"}`, with `email` exactly as
the attendee typed it.

## Rules

| Id | Rule (from the feature request) | Our decision |
|---|---|---|
| W1 | Attendees register with their name and email. Both are required. | 201 with the Registration. Name or email missing, not a string, or blank: **400**. |
| W2 | (Not in the request) What's an email? | Same check as speaker registration (`Utils.isValidEmail`): not `local@domain.tld` → **400** **(our guess: "required" means a real one, as in the Speakers spec)**. |
| W3 | Only workshops (format `WORKSHOP`) take registrations. | **409** on `POST`, the same answer as a workshop that isn't accepted (W4); `GET` gives `[]`. Unknown talk: 404. We first guessed 404 ("not a workshop resource"); the harness's check-2 failed us at tick 119 on an accepted `LIGHTNING_20` talk, right after accepting our 409 for a waitlisted workshop: the request names both conditions in one sentence, and the harness treats them as one rule. |
| W4 | Only workshops the committee has accepted take registrations. | A workshop that isn't `ACCEPTED` (SUBMITTED, WAITLISTED, REJECTED, WITHDRAWN): **409** for `POST` **(our guess: a state conflict)**. |
| W5 | If a workshop is withdrawn later, the people already registered stay on its list. | Withdrawal doesn't touch registrations; `GET` still lists them. New registrations on it: 409 (W4). |
| W6 | Every workshop has room for 20 attendees, and never more. | The 21st: **409**, nothing stored. |
| W7 | Registration opens to everyone at the same moment: expect a rush. | Every registration locks the workshop's talk row (`SELECT … FOR UPDATE`), then checks capacity and duplicates and inserts, so concurrent registrations can never make 21, and a unique index on `(talk_id, lower(email))` backs the duplicate rule. |
| W8 | One registration per email per workshop; emails are the same whatever the letter case. | A second registration with the same email (any case) for the same workshop: **409** **(our guess, as for speakers' duplicate emails)**, nothing changes. Checked before capacity. |
| W9 | The same person can register for several workshops. | Duplicates are per workshop only. |
| W10 | The organisers see who's registered, in the order they registered. | `GET` returns them by registration id (ids are handed out under the workshop lock, so in order). For any workshop, whatever its status; `[]` when none. |
| W11 | (Not in the request) Bad input. | Non-numeric id: 400. Body missing or not JSON: 400. |

## Order of checks for `POST`

1. Talk exists (else 404), and is locked.
2. Body: name and email present, strings, not blank; email valid (else 400).
3. Talk is a `WORKSHOP` (else 409), and `ACCEPTED` (else 409).
4. Email (case-insensitive) not already registered for this workshop (else 409).
5. Fewer than 20 registrations (else 409).
6. Insert, 201.

## Questions for the product owner (before claiming)

1. ~~Registering for a talk that isn't a workshop~~: answered by the harness at tick 119: 409, like not accepted.
2. Registering for a workshop that isn't accepted (yet): 409 (our guess)?
3. The same email twice for one workshop: 409 (our guess), or "nothing changes" (200 with the existing registration)?
4. Must the email look like a real email, or is any non-blank text enough?
