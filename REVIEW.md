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
- **Tests on the production Java** (11), with Testcontainers Postgres: 35 tests, run by us and by the `tests` gate.
- **Mutation check for concurrency**: we removed `FOR UPDATE` from `CoSpeakerDao.lockTalk` and watched
  `c12_concurrentAddsNeverMakeThree` fail (a talk got 4 co-speakers), then put it back.
- **Side-by-side regression check** (`compare.sh`): for changes that must not change behaviour (the faster
  `GET /talks`, the request logging) 89/89 responses identical; for co-speakers, identical except the new field.
- **Smoke gate in CI**: the exact image against a fresh Postgres, `/health` version, every `examples/*.hurl`. It
  caught its own first problem (Hurl wouldn't install on `ubuntu-latest`): no deploy, nothing lost.
- **The harness**, after the claim: transcripts of FAULTY checks plus our request log. It caught what we didn't (4).
- **A person** reads each diff and the agent's evidence before pushing, and asks "how do you know?" when the agent
  says "safe".

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| Speakers, Talk submissions, Ratings | claimed (base) | 0 | Given. Checked against the specs afterwards: compliant except a missing email format check, fixed in `9d6acef`; 15/15 checks OK throughout. |
| Co-speakers | claimed | 84 | 10 rule tests (C1–C12) green on Java 11, concurrency test proven to fail without the lock, `examples/co-speakers.hurl` green locally, in the smoke gate and against our live URL, regression check identical apart from `coSpeakerIds`, 15/15 base checks still OK after the deploy. **check-1 went FAULTY at tick 85** (see 4), fixed in `97cb7d9`. |
| Acceptance state machine | claimed | 100 | Our spec with every guess marked (`workflow/specs/acceptance.md`, product-owner questions listed); 12 tests (A1–A12) green on Java 11; both concurrency tests seen to fail without the lock (5 "winners" on one talk); `examples/acceptance.hurl` green locally and live; regression check 89/89 identical; **the harness's own acceptance traffic read before claiming** (`harness-traffic.py`, 2 runs, ~70 requests, no 5xx, every sequence answered as the spec says: same reviewer twice still blocks accepting, 6 concurrent decisions have one winner, withdrawn is final). Not seen in its traffic: re-waitlisting and co-speaker withdrawal (our guesses). |
| Spring Boot 4 | claimed | 106 | Done by a teammate on a branch (PR #1), merged and deployed before the claim. Before claiming: `/actuator/info` live reports the real running versions (Java 25.0.5, Boot 4.1.2, Framework 7.0.10); all 23 claimed checks OK on the new version with no new bleed; the old (Java 11 / Boot 2.7) and new (Java 25 / Boot 4, as the Docker image) side by side with `compare.py`: 78/89 identical, the 11 differences only the wording of Spring's error messages, no status code or success body changed; all Hurl files green on the new image; the upgrade's own `ApiCompatibilityTest` pins what upgrades break silently (trailing slash, date formats, field order, comments in JSON, error format). Production start-up 19 s (was 38 s originally). |
| Workshop registration | not yet | | |

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

**Every deploy costs points, tick 47.** Right after our first deploy, 5 base checks failed once on
`POST /speakers`. The log showed the new instance answering the harness's first burst in 2.8–4.7 s: the platform's
small CPU made the JVM start in 38 s and the first requests slow. **Why we missed it:** locally it starts in 3 s.
**What we changed:** JVM flags for start-up (C1 only, serial GC: 56 s → 25 s at 0.25 CPU in `docker --cpus`), a
read-only warm-up after start (`WarmUp.java`), and we bundle changes into fewer deploys.

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
  what an external checker sends.

## 6. One thing we'd do differently

Treat every decision the spec leaves open as a question, not an answer: list them before writing tests and get a
person (or the product owner) to confirm them. The one feature we got wrong was green on all our own checks; the
tests were faithful to a decision nobody had checked.
