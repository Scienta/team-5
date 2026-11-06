# Programme scheduling: our spec

Source: harness `/specs/scheduling/spec.md` (Version 1, drip feature released at tick 100, V: 3). No published
examples. Rule ids P1–P14 are used in `SchedulingTest` and `examples/scheduling.hurl` (P, not S, so they don't read
as slots). Decisions the request doesn't state are marked **(our guess)**; see `.claude/rules/spec-decisions.md`.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| `PUT` | `/programme/{room}/{slot}` | `{"talkId": <integer>}` | 200, the Entry |
| `GET` | `/programme/{room}/{slot}` | | 200, the Entry |
| `DELETE` | `/programme/{room}/{slot}` | | **204, no body (our guess: the request gives no response)** |

Entry: `{"room": "R1", "slot": "S4", "start": "13:30", "end": "15:30", "talkId": 7}`.

## The venue (fixed)

Rooms `R1`, `R2`, `R3`. Slots, the same in every room:

| Slot | Start | End |
|---|---|---|
| `S1` | 09:00 | 10:00 |
| `S2` | 10:15 | 11:15 |
| `S3` | 11:30 | 12:30 |
| `S4` | 13:30 | 15:30 |
| `S5` | 15:45 | 16:45 |

## Rules

| Id | Rule (from the feature request) | Our decision |
|---|---|---|
| P1 | Three rooms and five slots, fixed; nobody can add or change them. | Any other room or slot in the path (`R0`, `R4`, `r1`, `s1`, `S6`, `Room1`, …): **404** for `PUT`, `GET` and `DELETE` **(our guess: a room or slot that doesn't exist is a path resource that doesn't exist; exact capitals only, as for formats and levels)**. |
| P2 | Times are `HH:mm`, 24-hour (`09:00`, not `9:00`). | `start` and `end` on every Entry, from the table above. |
| P3 | The committee puts a talk in a room and slot. | `PUT` with `{"talkId": n}`: 200 with the Entry. |
| P4 | … looks up what's in it. | `GET`: 200 with the Entry. An empty room and slot: **404** **(our guess: nothing there to show)**. |
| P5 | … and takes the talk out again. A talk that's been taken out can go somewhere else. | `DELETE`: 204, the room and slot is empty again and the talk can be put anywhere. An empty room and slot: **404** (rules: removing what isn't there is not success; the harness `DELETE`s empty slots before its runs, so it expects to get past that). |
| P6 | Only accepted talks go on the programme. | Talk not `ACCEPTED` (SUBMITTED, WAITLISTED, REJECTED, WITHDRAWN): **409**. |
| P7 | A talk must fit its slot: a workshop needs the long slot, everything else fits anywhere. | `WORKSHOP` (120 min) only in `S4`, anywhere else **409** **(our guess: the talk conflicts with the slot; 409 like the other programme rules)**. Every other format fits every slot, `S4` included. |
| P8 | One talk per room and slot. | Another talk in an occupied room and slot: **409**. The same talk in the same place again changes nothing: **200** with the Entry (PUT is idempotent, and no rule is broken). We first guessed 409 for that; the harness's check-1 failed it at tick 136 (PUT, GET, the same PUT again). |
| P9 | A talk is on the programme only once. To move a talk, take it out first. | The talk is already in another room or slot: **409**. |
| P10 | Nobody speaks in two rooms at the same time: main speaker and co-speakers of talks in the same slot all differ. | The talk's main speaker or any co-speaker is a main speaker or co-speaker of a talk in the same slot in another room: **409**. Other slots don't matter. |
| P11 | The rules are checked when a talk is put on the programme; if a talk changes afterwards, the programme stays. | Nothing re-checks the programme: a talk withdrawn after being put stays; co-speakers added later can clash. `GET` still shows it. |
| P12 | Several committee members work at once; the programme must never break the rules. | `PUT` locks the slot's three rows (`SELECT … FOR UPDATE`, one row per room and slot exists from start-up), checks P6–P10, then writes. A unique index on the talk keeps P9 across slots too: the loser of a race gets **409**. |
| P13 | (Not in the request) Bad input. | Body missing, not JSON, `talkId` missing, not a whole number: **400**. |
| P14 | (Not in the request) Unknown talk. | `talkId` that isn't a talk: **400**, bad input like the other body errors (P13), as for an unknown `speakerId` on `POST /talks`. We first guessed 404; the harness's check-1 failed it at tick 131, right after accepting 404 for an unknown room and 400 for `talkId: 1.5`. |

## Order of checks

`PUT`: room and slot exist (404) → body valid (400) → talk exists (400) → lock the slot → already exactly there (200, nothing changes) → talk accepted (409) → fits
the slot (409) → room and slot empty (409) → talk not on the programme elsewhere (409) → no speaker in two rooms
(409) → write → 200.

`GET`: room and slot exist (404) → a talk there (404) → 200.

`DELETE`: room and slot exist (404) → a talk there (404) → empty it → 204.

## Not changed by this feature (our guess)

- Talks don't show where they're scheduled; only the programme endpoints do.
- Withdrawing a talk or changing its co-speakers doesn't touch the programme (P11).

## Questions for the product owner (before claiming)

1. An unknown room or slot (`R4`, `r1`, `S6`): 404 (our guess) or 400?
2. `DELETE`: 204 with no body (our guess), or 200 with the Entry that was removed? And on an empty room and slot,
   404 (our guess) or 204?
3. `GET` on an empty room and slot: 404 (our guess), or 200 with an Entry without `talkId`?
4. ~~The same talk in the same room and slot again~~: answered by the harness at tick 136: 200, nothing changes.
5. A workshop outside `S4`: 409 (our guess) or 400?
6. ~~An unknown `talkId`~~: answered by the harness at tick 131: 400.

## What the harness tried (filled in after deploying, before claiming)

Before the deploy, with no `/programme` endpoint yet (every answer 404), the checks already sent: unknown rooms and
slots (`R0`, `R4`, `Room1`, `r1`, `s1`, `S0`, `S6`, `slot2`) with `PUT`, `GET` and `DELETE`; a `DELETE` of every
room and slot before each run; and bursts of `PUT`s to `R1/S2` and `R2/S2` in the same second (P12). The sequences
and our answers after the deploy: `python3 -I workflow/scripts/harness-traffic.py '/programme' --all`.
