# HTTP answers the harness accepts, and how it tests

Evidence, not theory: every row is a situation from one of our features, the answer we gave, and what the harness did
with it (a claimed check staying OK, or failing). Use it to decide the open questions in a new feature's spec
(`.claude/rules/spec-decisions.md`), and add a row whenever a check confirms or rejects an answer.

## Answers, by situation

| Situation | Answer | Evidence |
|---|---|---|
| Created something (`POST` of a new resource) | **201** with the resource | speakers, talks, workshop registrations: all checks OK |
| Changed something, or an action on it (`PUT`, `DELETE`, `POST …/withdraw`) | **200** with the resource as it is now | ratings, co-speakers, acceptance: all checks OK |
| Doing again what the request says "changes nothing" (adding an existing co-speaker), or what it says replaces (rating again) | **200**, unchanged / replaced | co-speakers 3/3, ratings 5/5 |
| An id in the path that doesn't exist (talk, speaker, workshop; huge ids like 2084049616) | **404** | every feature probes it; all OK |
| Removing a relation that isn't there (`DELETE` a co-speaker twice) | **404**, nothing changes | **200 failed** co-speakers/check-1 at tick 85; 404 OK since |
| Input missing, wrong type, blank or not one of the allowed values (`null`, `7` for a string, `1.5` for an id, `" \t "`, `"accepted"` for `ACCEPTED`, `SUBMITTED` as a decision, a non-numeric path id, no body) | **400**, nothing changes | all features; all OK |
| Input that breaks a rule about itself (the main speaker as their own co-speaker; an id in the body that doesn't exist, e.g. `speakerId` on `POST /talks`) | **400** | co-speakers 3/3, talks 6/6 |
| A limit reached (3 talks per speaker, 2 co-speakers, 20 per workshop) | **409**, nothing stored | talks, co-speakers, workshop registration: OK |
| A duplicate (speaker email, registration email, any letter case) | **409** | speakers 4/4, workshop registration |
| A state transition the rules don't allow (deciding on an accepted, rejected or withdrawn talk; withdrawing a rejected one) | **409**, nothing changes | acceptance 5/5 from the first tick |
| A precondition not met (accepting with fewer than 2 reviewers) | **409** | acceptance 5/5 |
| Something that exists but doesn't qualify for the operation (an accepted talk that isn't a workshop; a workshop that isn't accepted) | **409**, the same for every way of not qualifying | **404 failed** workshop-registration/check-2 at tick 119 (it accepted 409 for "not accepted" in the same run); **409 confirmed**: 4/4 OK from tick 126 (`d52afa3`) |
| Someone who isn't allowed to (a speaker withdrawing another speaker's talk) | **403** | acceptance 5/5 |

Rules of thumb that follow: **404 only for ids that don't exist**; a sentence in the request with several conditions
is one rule with one answer; "changes nothing" is 200 only where the request says so; everything that refuses leaves
the data exactly as it was.

## What the harness's checks try

Seen in our request log (`workflow/scripts/harness-traffic.py`) across all features. Write a test for each before
claiming.

- **The happy path first**, then each rule of the request in turn, on fresh data (new speakers and talks every run,
  emails like `x-…@example.test`).
- **The same operation twice in a row**: delete twice, withdraw twice, decide again, register again.
- **The same value in another letter case**: `X-…@EXAMPLE.TEST` for an email registered in lower case.
- **Unknown ids**: huge numbers (about 2 000 000 000) in the path and in bodies.
- **Wrong types and blanks**: `null`, numbers for strings, `1.5` for ids, whitespace-only strings, missing fields,
  `{}`, lower-case enum values, values from the right enum that aren't allowed here (`SUBMITTED` as a decision).
- **A look-alike that doesn't qualify**: an *accepted* presentation where a workshop is required, a *waitlisted*
  workshop where an accepted one is.
- **Bursts at the same moment**: 6 decisions on one talk, about 27 registrations for 20 places, two workshops rushed
  at once. Exactly one winner, exactly the limit, never a 5xx.
- **Read-backs after every change and every refusal**: `GET` the resource or list to see that a refused request
  changed nothing, and that the order is right.
- **Its checks run every minute for every released feature, claimed or not**, so the traffic is there to read before
  a claim. It shows our answers, not its expectations: an answer it disagrees with only shows after the claim.
