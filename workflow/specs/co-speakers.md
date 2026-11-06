# Co-speakers: our spec

Source: harness `/specs/co-speakers/spec.md` (Version 1, drip feature released at tick 0). The feature request gives
the business rules and the API; this file adds the decisions it leaves to us (status codes, edge cases, order of
checks). Each rule has an id used in `CoSpeakersTest` and `examples/co-speakers.hurl`.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| `PUT` | `/talks/{id}/co-speakers/{speakerId}` | none (ignored if sent) | 200, the Talk |
| `DELETE` | `/talks/{id}/co-speakers/{speakerId}` | none | 200, the Talk |

Every Talk, wherever it's shown, gets `coSpeakerIds`: the co-speakers' speaker ids in the order they were added,
`[]` when there are none.

## Rules

| Id | Rule (from the feature request) | Our decision |
|---|---|---|
| C1 | Co-speakers are other registered speakers added to a talk afterwards. | `PUT` adds; 200 with the Talk. |
| C2 | They can be removed again. | `DELETE` removes; 200 with the Talk. |
| C3 | At most two co-speakers. | A third distinct co-speaker: **409**, nothing changes. |
| C4 | The main speaker can't be a co-speaker of their own talk. | **400**, nothing changes. |
| C5 | Shown in the order they were added. | Order of the successful add. Removed and added again goes to the end. |
| C6 | Adding someone who is already a co-speaker changes nothing. | **200**, the Talk unchanged, order kept. Also when the talk already has two (it isn't a third). |
| C7 | Every talk shows its co-speakers. | `coSpeakerIds` on every Talk response: `POST /talks`, `GET /talks/{id}`, `GET /talks` (all, by speaker, ranked), `GET /speakers/{id}/talks`, and the responses of `PUT`/`DELETE` here. |
| C8 | A speaker's own talk list shows only talks where they're the main speaker. | `GET /speakers/{id}/talks` and `GET /talks?speakerId=` unchanged: main speaker only. |
| C9 | Co-speaking doesn't use up the 3 submissions; a speaker at the limit can still co-speak. | The limit counts `talk.speaker_id` only, as before. |
| C10 | (Not in the request) Removing someone who isn't a co-speaker. | **200**, the Talk unchanged (idempotent, like C6). |
| C11 | (Not in the request) Unknown talk or unknown speaker. | **404**. The talk is checked first, then the speaker. Non-numeric ids: 400. |
| C12 | (Not in the request, but the committee clicks at once) Concurrent adds. | The talk row is locked (`SELECT … FOR UPDATE`) for every add and remove, so concurrent adds can never make three, and the same speaker added twice at once is one row. |

## Order of checks for `PUT`

1. Talk exists, locked (else 404).
2. Speaker exists (else 404).
3. Speaker is the main speaker (400).
4. Already a co-speaker: return the talk unchanged (200).
5. Already two co-speakers (409).
6. Add, return the talk (200).

## Open questions for the product owner

- 409 for a third co-speaker and 400 for the main speaker: does the business care which 4xx?
- Should co-speakers be changeable on a talk that is `WITHDRAWN` or `REJECTED` (Acceptance feature)? For now: yes, no status check.

## Storage

`talk_co_speaker (talk_id, speaker_id, seq BIGSERIAL, PRIMARY KEY (talk_id, speaker_id))`, ordered by `seq`. Loaded
into every Talk by the same query that loads ratings (a `LATERAL` `array_agg`), so lists stay one query.
