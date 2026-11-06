# REVIEW

> **Showcase brief (17:00, 4 minutes, hard stop).** You screen-share this repo from the shared
> presenter laptop, in a random order drawn at 17:00. No slides, and no harness screens.
>
> 1. **Our workflow in one minute:** section 1, pointing at the files in `workflow/` and `.claude/`.
> 2. **One story:** a bug our review caught before the harness did, or one the harness caught that
>    we missed, and why. Use the claim log.
> 3. **One artifact we'd reuse**, opened on screen.
>
> Keep this file as a running log from t = 0, so 17:00–17:05 is for tidying, not writing. To be
> eligible to win, every heading below needs some text. The comments under each heading are prompts
> and don't count; replace them with your own words.

## 1. Workflow

What we actually do, from feature request to claim (one person driving, Claude Code doing most of the typing):

1. **Read the feature request** from the harness (`/specs/<slug>/spec.md`) and check for published examples
   (`/specs/<slug>/examples.hurl`; none so far for drip features).
2. **Write our own spec** in `workflow/specs/<slug>.md`: every rule gets an id (C1, C2, …), and every decision the
   request leaves open (status codes, edge cases, order of checks) is written down with *why*, marked as our guess.
   Example: [`workflow/specs/co-speakers.md`](workflow/specs/co-speakers.md).
3. **Tests per rule**, named after the ids (`CoSpeakersTest.c3_atMostTwo`, …), including concurrency. A concurrency
   test is only trusted after we've seen it fail with the lock removed.
4. **A Hurl example per feature** in `examples/`, which `examples/run.sh` and the deploy's smoke gate both run.
5. **Regression check** against what's live: [`workflow/scripts/compare.sh`](workflow/scripts/compare.sh) builds
   `origin/main` and the working tree, sends both the same ~90 requests and prints every difference.
6. **Push = deploy**, but only through the gates in `.github/workflows/deploy.yml` (`tests`, then `smoke`).
7. **Verify live**: [`workflow/scripts/feedback.py`](workflow/scripts/feedback.py) (deployed version, every check,
   bleed) and the feature's Hurl file against our URL.
8. **Read what the harness tries before claiming.** The harness runs a feature's checks every minute even while
   it's unclaimed, and our request log records them:
   [`workflow/scripts/harness-traffic.py`](workflow/scripts/harness-traffic.py) shows each sequence its checks try
   and what we answered. We compare that with our spec's guesses, then a person claims. (Added after co-speakers:
   the log would have shown "`DELETE` twice → 200" before the claim.)
9. **After every claim or deploy**: watch `feedback.py`; for a FAULTY check, read its transcript and our request log
   (`RequestLoggingFilter`), fix, and write it up in section 4.

Rules for the agent: [`.claude/rules/`](.claude/rules/). Checklist before claiming:
[`workflow/checklists/before-claim.md`](workflow/checklists/before-claim.md).

## 2. How we reviewed agent output

- **Against the spec, rule by rule**: the agent maps every rule in the feature request to code and to a test, in a
  table, before we accept the change (sections "Rules" in `workflow/specs/*.md`).
- **Tests on the production Java** (25 since the Spring Boot 4 upgrade; 11 before), with Testcontainers Postgres:
  60 tests, run by us and by the `tests` gate. Without a local JDK 25 we run them in Docker with
  [`workflow/scripts/mvn25.sh`](workflow/scripts/mvn25.sh).
- **Mutation check for every concurrency rule**: we remove the `FOR UPDATE` and watch the test fail, then put it
  back. Co-speakers: a talk got 4 co-speakers (max 2). Acceptance: 5 committee decisions "won" on one talk, and a
  reject and a withdraw both succeeded. Workshop registration: 22 attendees in 20 places. A concurrency test we
  haven't seen fail doesn't count as evidence.
- **Side-by-side regression check** (`compare.sh`): for changes that must not change behaviour (the faster
  `GET /talks`, the request logging) 89/89 responses identical; for co-speakers, identical except the new field.
- **Smoke gate in CI**: the exact image against a fresh Postgres, `/health` version, every `examples/*.hurl`. It
  caught its own first problem (Hurl wouldn't install on `ubuntu-latest`): no deploy, nothing lost.
- **The harness's own traffic, before the claim**: the harness runs every released feature's checks each minute,
  claimed or not, and our request log records them. After deploying a feature we read what its checks try with
  [`workflow/scripts/harness-traffic.py`](workflow/scripts/harness-traffic.py) and compare our answers with our
  spec. Acceptance was claimed this way and was 5/5 OK on its first tick; co-speakers, claimed without it, wasn't.
- **The harness**, after the claim: transcripts of FAULTY checks plus our request log. It caught what we didn't (4).
- **For the framework upgrade** (a teammate, on a branch): `ApiCompatibilityTest` pins what upgrades break silently,
  [`workflow/api-diff.sh`](workflow/api-diff.sh) diffs the old and new build's answers, and we ran `compare.py`
  between the Java 11 build and the Java 25 Docker image: only the wording of Spring's error messages differed.
- **A person** reads each diff and the agent's evidence before pushing, and asks "how do you know?" when the agent
  says "safe".

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| Speakers, Talk submissions, Ratings | claimed (base) | 0 | Given. Checked against the specs afterwards: compliant except a missing email format check, fixed in `9d6acef`; 15/15 checks OK throughout. |
| Co-speakers | claimed | 84 | 10 rule tests (C1–C12) green on Java 11, concurrency test proven to fail without the lock, `examples/co-speakers.hurl` green locally, in the smoke gate and against our live URL, regression check identical apart from `coSpeakerIds`, 15/15 base checks still OK after the deploy. **check-1 went FAULTY at tick 85** (see 4), fixed in `97cb7d9`. |
| Acceptance state machine | claimed | 100 | Our spec with every guess marked (`workflow/specs/acceptance.md`, product-owner questions listed); 12 tests (A1–A12) green on Java 11; both concurrency tests seen to fail without the lock (5 "winners" on one talk); `examples/acceptance.hurl` green locally and live; regression check 89/89 identical; **the harness's own acceptance traffic read before claiming** (`harness-traffic.py`, 2 runs, ~70 requests, no 5xx, every sequence answered as the spec says: same reviewer twice still blocks accepting, 6 concurrent decisions have one winner, withdrawn is final). Not seen in its traffic: re-waitlisting and co-speaker withdrawal (our guesses). |
| Spring Boot 4 | claimed | 106 | Done by a teammate on a branch (PR #1), merged and deployed before the claim. Before claiming: `/actuator/info` live reports the real running versions (Java 25.0.5, Boot 4.1.2, Framework 7.0.10); all 23 claimed checks OK on the new version with no new bleed; the old (Java 11 / Boot 2.7) and new (Java 25 / Boot 4, as the Docker image) side by side with `compare.py`: 78/89 identical, the 11 differences only the wording of Spring's error messages, no status code or success body changed; all Hurl files green on the new image; the upgrade's own `ApiCompatibilityTest` pins what upgrades break silently (trailing slash, date formats, field order, comments in JSON, error format). Production start-up 19 s (was 38 s originally). |
| Workshop registration | claimed | 119 | Our spec with guesses marked; 9 tests (W1–W11) green on Java 25; the rush test seen to fail without the lock (22 in 20 places), the same-email rush also stopped by the unique index; `examples/workshop-registration.hurl` green locally and live; regression 89/89; the harness's traffic read before claiming (249 requests over several runs, no 5xx, rushes held at exactly 20, the uppercase duplicate refused); an independent cold review by the `spec-reviewer` agent, which agreed on all behaviour and named our 404/409 codes as the top risk. **check-2 went FAULTY at tick 119** on exactly that risk (see 4), fixed in `d52afa3`. |
| Programme scheduling | claimed | 130 | Done by a teammate on a branch (PR #2) with our workflow: spec from the template with P1–P14 and six guesses listed for the product owner (`workflow/specs/scheduling.md`); 18 tests in `SchedulingTest`, each concurrency test seen to fail with its lock or unique index removed; 78 tests and 7/7 Hurl files (121 requests) green; `compare.sh` 89/89 identical; the harness's traffic read **before** the deploy (unknown rooms and slots, a `DELETE` of every slot each run, bursts on one slot). Claimed right after the deploy, without reading its traffic against the new code. **check-1 went FAULTY at tick 131** on guess P14, unknown `talkId` (400 in `1207898`, confirmed at tick 136), then on guess P8, the same `PUT` again (200 in `5e0b55e`, see 4). **6/6 OK from tick 142**; the two guesses cost about 10 points. Checks 2–6 OK from the first tick. |

## 4. What the harness caught that we didn't

**Co-speakers, check-1, tick 85: removing the same co-speaker twice.** The feature request doesn't say what
happens when you remove someone who isn't a co-speaker. We decided "200, nothing changes" (idempotent, by analogy
with "adding someone who is already a co-speaker changes nothing"), wrote it in our spec as rule C10, and then wrote
a test and a Hurl example *for our decision*. Everything was green, and the harness's check removed a co-speaker
twice and failed us on the second `DELETE`: it expects an error (404). **Why we missed it:** our tests checked our
own guess, not the business; nobody asked the product owner, and the analogy was ours, not the spec's ("changes
nothing" was only promised for adding). **What we changed:** the rule in `.claude/rules/spec-decisions.md`: for
anything the request doesn't say, use the conventional HTTP answer (a relation that doesn't exist is 404, also on
`DELETE`), mark it as a guess in the spec, and put the guesses in front of a person, who asks the product owner
before claiming.

**Programme scheduling, check-1, tick 131: an unknown id in the body.** `PUT /programme/R3/S1 {"talkId":
2049035110}` got 404 ("talk not found") from us; the check had just accepted 404 for an unknown room and 400 for
`talkId: 1.5`, and failed the 404. An id in the body that doesn't exist is bad input (400), like an unknown `speakerId`
on `POST /talks`, which the talk checks have accepted all day. **Why we missed it:** it was the guess our own review
ranked riskiest, and the claim came straight after the deploy, before anyone read the harness's traffic against the
new code (the traffic was only read before the deploy, when every answer was a 404). **What we changed:** the fix in
`1207898`, a new row in `workflow/http-conventions.md` ("an id in the body that doesn't exist → 400"), and the
reminder that the traffic review belongs between deploy and claim, not before the deploy. The fix got the check one
step further, to its next failure at tick 136: the **same `PUT` again** (same talk, same room and slot) got 409 from
us, and the check expects 200: a `PUT` that sets exactly what's already there breaks no rule. Fixed in the next commit
and added to `http-conventions.md`. One FAULTY check can hide several wrong guesses behind each other.

**Workshop registration, check-2, tick 119: one rule, two answers.** "Only workshops that the committee has
accepted take registrations." We answered a waitlisted workshop with 409 (state) and an accepted talk that isn't a
workshop with 404 ("no such workshop"). The check accepted the 409 and failed the 404, in the same run: it treats the
sentence as one rule. **Why we missed it:** we saw the risk (the reviewer agent ranked it first, and the harness's
traffic showed it probing an accepted presentation), but the traffic shows our answers, not its expectations, and we
claimed on a guess we couldn't verify. **What we changed:** a rule in `.claude/rules/spec-decisions.md` (conditions in
one sentence get one answer; 404 only for ids that don't exist), and the fix in `d52afa3`. It cost 5 points (bleed
−7.9 → −12.9) over the six ticks until the fix was live; 4/4 OK from tick 126.

**Every deploy costs points, tick 47.** Right after our first deploy, 5 base checks failed once on
`POST /speakers`. The log showed the new instance answering the harness's first burst in 2.8–4.7 s: the platform's
small CPU made the JVM start in 38 s and the first requests slow. **Why we missed it:** locally it starts in 3 s.
**What we changed:** JVM flags for start-up (C1 only, serial GC: 56 s → 25 s at 0.25 CPU in `docker --cpus`), a
read-only warm-up after start (`WarmUp.java`), and we bundle changes into fewer deploys.

**Our gate's first run failed, and that was the gate working (tick ~88).** The new `smoke` job couldn't install Hurl
on `ubuntu-latest` (a renamed `libxml2`), so the deploy never ran and the live version kept serving. Pinned the job
to `ubuntu-24.04`. Lesson: a gate that fails closed costs time, not points.

**Ratings, check-3, tick 75: unexplained.** One FAULTY run on `PUT /talks/{id}/ratings/{reviewerId}`; our log shows
correct answers (30 × 200, 2 × 400, 1 × 404, nothing slow), and the transcript was gone when we looked. It ran at
12:41:59, a second before the minute changed (`ratedAtDisplay` has minutes), so possibly a clock-boundary flake.
Lesson: read the transcript as soon as `feedback.py` shows FAULTY; it only keeps the latest faulty run.

## 5. Reusable artifacts

- [`workflow/scripts/compare.sh`](workflow/scripts/compare.sh) + [`compare.py`](workflow/scripts/compare.py):
  side-by-side regression check of two versions of an HTTP service, with ids, timestamps and test data masked and
  `IGNORE_FIELDS` for intended additions. The one we'd take to every client refactor and framework upgrade.
- [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) jobs `tests` and `smoke`: deploy only an image
  that has started against a real database and passed the examples.
- [`workflow/scripts/feedback.py`](workflow/scripts/feedback.py): one command for "what's live, and is it green?".
- [`workflow/specs/co-speakers.md`](workflow/specs/co-speakers.md): the spec format (rule ids, decisions with
  reasons, order of checks, open questions), and [`workflow/checklists/before-claim.md`](workflow/checklists/before-claim.md).
- `RequestLoggingFilter.java`: one line per request with the body and the error reason, the cheapest way to see
  what an external checker sends. Together with
  [`workflow/scripts/harness-traffic.py`](workflow/scripts/harness-traffic.py) it turns any black-box checker into
  readable test cases: run the checks against a deployed but unclaimed feature and read what they try.
- [`.claude/skills/drip-feature/SKILL.md`](.claude/skills/drip-feature/SKILL.md): the whole feature workflow as a
  Claude Code skill, and [`.claude/agents/spec-reviewer.md`](.claude/agents/spec-reviewer.md): a reviewer agent that
  reads the feature request and our code cold and lists where they disagree, before a person claims.
- [`workflow/specs/TEMPLATE.md`](workflow/specs/TEMPLATE.md): the spec format, distilled from our three specs.
- [`workflow/http-conventions.md`](workflow/http-conventions.md): which HTTP answer for which situation, each row
  backed by a harness check that accepted or rejected it, plus the catalogue of what black-box checks try (twice in a
  row, other letter case, look-alikes that don't qualify, bursts, read-backs). The thing we'd most like to have had at
  tick 0.
- `ApiCompatibilityTest.java` and [`workflow/api-diff.sh`](workflow/api-diff.sh) (from the Spring Boot 4 upgrade):
  pin and diff the API's observable behaviour across a framework upgrade.
- [`workflow/scripts/mvn25.sh`](workflow/scripts/mvn25.sh): build and test on the production JDK in Docker, when a
  laptop doesn't have it.

## 6. One thing we'd do differently

Treat every decision the spec leaves open as a question, not an answer: list them before writing tests and get a
person (or the product owner) to confirm them. The one feature we got wrong was green on all our own checks; the
tests were faithful to a decision nobody had checked.
