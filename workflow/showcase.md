# Showcase script (17:00, 4 minutes, hard stop)

Screen-share this repo. No slides, no harness screens. Final numbers (tick 218, when the harness stopped ticking)
are filled in below.

## 0:00–1:00 Our workflow (REVIEW.md section 1)

Open `REVIEW.md`, section 1, and `.claude/skills/drip-feature/SKILL.md` side by side.

- "Every feature goes the same way: the feature request, then **our own spec** with every open decision marked as a
  guess (`workflow/specs/`), tests per rule, a Hurl example, a regression check, push through two gates."
- "Then the step that made the difference: **we read what the harness's checks try against the deployed code
  before anyone claims** (`workflow/scripts/harness-traffic.py`). The harness runs every feature's checks each
  minute, claimed or not, and our request log records them."
- "It's all a Claude Code skill, two rule files in `.claude/rules/`, a checklist and a cold reviewer agent."

## 1:00–2:45 One story: the harness caught our guesses, and that changed the workflow

Open `REVIEW.md` section 4, then section 3.

- "Co-speakers: the request didn't say what removing someone who isn't a co-speaker does. We decided '200, nothing
  changes', wrote a test *for our decision*, and everything was green. The harness removed a co-speaker twice and
  failed us on the second `DELETE`: it wanted 404. Our tests were faithful to a decision nobody had checked."
- "Workshop registration and scheduling failed the same way: each time on a guess, each time with every one of our
  own checks green. Scheduling even had two wrong guesses behind each other in one check."
- "So we changed the workflow: deploy first, read the harness's traffic against the new code, then claim. The
  three features claimed that way, acceptance, programme export and the ratings change request, were all green
  from their first tick."
- Numbers: all 11 released features claimed (3 base, 6 drip features, 2 change requests), every active check green
  (43/43), 2 285 points: 2 336 earned, 51 bled. About 19 of the 51 were those guesses, about 25 the harness's
  afternoon load phase ("2x"), the rest cold starts and two one-offs (`workflow/lessons.md`).

## 2:45–3:45 One artifact we'd reuse: `workflow/http-conventions.md`

Open it.

- "Every row is a situation, the HTTP answer, and the harness check that accepted or rejected it. 404 only for ids
  that don't exist; a rule in one sentence gets one answer; `PUT` that sets what's already there is 200, a repeated
  `DELETE` is 404."
- "And the second half: what black-box checks try. Twice in a row, another letter case, look-alikes that don't
  qualify, bursts, read-backs after every refusal. It's the thing we'd most like to have had at 9 o'clock, and it
  works for any client with an external acceptance test."

## 3:45–4:00 Close

- "One thing we'd do differently: treat every open decision as a question, not an answer." (`REVIEW.md` section 6)

## If there's a question

- The deploy pipeline: `.github/workflows/deploy.yml`, tests and a smoke run of the exact image as gates; push to
  live in about 2 minutes.
- Concurrency: every lock test is only trusted after we've seen it fail with the lock removed (`REVIEW.md` section 2).
- The framework upgrade (Spring Boot 2.7 → 4, Java 11 → 25): done on a branch, checked side by side with
  `workflow/scripts/compare.sh`.
