# Programme export: our spec

Source: harness `/specs/programme-export/spec.md` (Version 1, released at tick 133, V: 1). Published examples: no.
Rule ids E1–E12 are used in `ProgrammeExportTest` and `examples/programme-export.hurl`. Decisions the request doesn't
state come from `workflow/http-conventions.md` where it has a row, and are otherwise marked **(our guess)**; see
`.claude/rules/spec-decisions.md`.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| `GET` | `/programme` | | 200, `application/json`: a list of ProgrammeRow |
| `GET` | `/programme.csv` | | 200, `text/csv;charset=UTF-8`: the same rows as CSV |

ProgrammeRow: `{"slot":"S4","start":"13:30","end":"15:30","room":"R1","talkId":7,"title":"…","format":"WORKSHOP",
"speakers":["Grace Hopper","Ada Lovelace"]}`, fields in this order. No existing response changes.

## Rules

| Id | Rule (from the feature request) | Our decision |
|---|---|---|
| E1 | Two read-only views of everything on the programme: JSON for the website, CSV for the print shop. | `GET /programme` and `GET /programme.csv`. Everything in `programme` with a talk, whatever the talk's status now: a talk withdrawn after it was scheduled stays on the programme (scheduling P11), so it's exported **(our guess: "everything on the programme")**. |
| E2 | One row per scheduled talk, nothing else, in the same order in both. | Both views come from one query and one sort. |
| E3 | Earliest start first, and within a slot by room (`R1`, `R2`, `R3`). | Sorted by the slot's start time (S1…S5), then by room. |
| E4 | Each row: the slot, its start and end (as in Programme scheduling), the room, the talk's id, title and format. | `start` and `end` from the scheduling table, `HH:mm`. |
| E5 | … and speakers as they are right now. | Names read at export time from `speaker` and `talk_co_speaker`, so a renamed speaker or a co-speaker added after scheduling shows. |
| E6 | Speakers by name: the main speaker first, then co-speakers in the order they were added. | `speakers` in JSON is a list in that order. |
| E7 | In the CSV the speakers are one field, separated by a semicolon and a space. | `Grace Hopper; Ada Lovelace`. |
| E8 | The CSV is UTF-8, served as `text/csv`, even to clients that ask for JSON. | `Content-Type: text/csv;charset=UTF-8` whatever the `Accept` header (also `application/json`), always 200. UTF-8 bytes, no byte order mark. |
| E9 | It starts with exactly this header line: `slot,start,end,room,talkId,title,format,speakers`. | Written as is, first line. |
| E10 | Standard CSV (RFC 4180) with Unix line endings, every line ending in a newline, no blank line at the end. | Every line, the header included, ends in `\n` (no `\r`); nothing after the last row's `\n`. |
| E11 | Fields are quoted only when they have to be (a comma, a quote or a line break), as in `"Kotlin, ""really"" fast"`; otherwise as they are. | Quoted if the field has `,`, `"`, `\n` or `\r`; a `"` inside is doubled. Leading or trailing spaces don't cause quoting **(our guess: RFC 4180 keeps spaces as part of the field)**. |
| E12 | An empty programme is an empty list, or a CSV with just the header. | `[]`, and `slot,start,end,room,talkId,title,format,speakers\n`. |

## Order of checks

Nothing to check: both are reads of everything, with no parameters. Any query string is ignored **(our guess)**.

## Not changed by this feature

- `GET`, `PUT`, `DELETE /programme/{room}/{slot}` stay as they are. `/programme/` (trailing slash) answers like
  `/programme`, as every path does.
- The admin export `/admin/talks.csv` keeps its own semicolon format.

## Questions for the product owner (before claiming)

1. Does a talk that was withdrawn after it was scheduled still appear in the export (our guess: yes, it's still on the
   programme)?
2. Leading or trailing spaces in a title: written as they are, unquoted (our guess, RFC 4180), or quoted?

## What the harness tried (filled in after deploying, before claiming)

Before the deploy (every answer 404, the endpoints didn't exist): `GET /programme` and `GET /programme.csv`, a few
times a minute. After the deploy: `python3 -I workflow/scripts/harness-traffic.py '^GET /programme(\.csv)?' --all`.
