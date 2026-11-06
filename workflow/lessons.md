# What we learned about the game itself

How a day of drip features, change requests and a black-box checker actually behaved, and what we'd do from 9
o'clock next time. The stories behind each point are in `REVIEW.md` section 4; the HTTP answers in
`workflow/http-conventions.md`.

## Where the points went

About 51 points bled out of about 2 100 earned (tick 207):

| Cause | Points | Avoidable? |
|---|---|---|
| Guesses the harness disagreed with, after a claim (co-speakers 404, workshops 409, scheduling 400 and 200) | ~19 | Yes: all three were in the harness's traffic before the claim |
| The harness's doubled load ("2x", from tick 192): bursts whose requests reached us seconds late or not at all, plus the deploys that fell in that window | ~25 | Mostly not by us; see "Late in the day" |
| Cold starts after deploys (first deploy, and switchovers under load) | ~4 | Partly: faster start-up, warm-up, fewer deploys |
| Two one-off rating failures at a minute change | ~2 | No |

Nothing was lost to a feature being wrong in a way our own tests could have caught. Every loss was either a guess
or the platform.

## Drip features

- **A feature request is a list of business rules, not a test plan.** The status codes, the edge cases and what
  "again" means are left open, and that's exactly what the checks test. Write every open decision down as a guess
  before writing code (`workflow/specs/TEMPLATE.md`).
- **The harness runs every released feature's checks every minute, claimed or not.** Before the deploy you see it
  probing your 404s; after the deploy you see every sequence it tries, against your real answers. That's the best
  evidence there is before a permanent claim. Features claimed after reading it: green from the first tick
  (acceptance, programme export, ratings change request). Features claimed on guesses: each failed on a guess
  (co-speakers, workshop registration, scheduling).
- **The traffic shows our answers, not the harness's expectations.** For a guess the traffic can't settle, use the
  most conventional answer (`http-conventions.md`), and claim knowing that one fix may follow.
- **A failing check stops at its first wrong answer**, so one FAULTY check can hide several wrong guesses behind each
  other (scheduling: unknown id, then the repeated `PUT`). After a fix, read the next transcript, not just the status.
- **Claiming early pays.** A claimed check earns every tick it's green, so a correct feature claimed ten minutes
  sooner is worth more than a perfect one claimed later. The cost of a wrong guess was 3 to 10 points; the fix took
  minutes because the transcript said exactly where.
- **Teammates on separate features in parallel worked** once everyone used the same template, conventions and
  checklist. The pull hook (`.claude/hooks/new_docs.py`) surfaced each teammate's new spec, and two reviews of a
  colleague's guesses caught the risky ones before or right after their claim.

## Change requests

- **A change request retires the checks it replaces when it's released** (the feedback says `notes: ["retired"]`,
  status `None`). So the new behaviour can go live before the claim without breaking the claimed feature: check which
  checks are retired, deploy, read the traffic, claim. Ratings and the submission cap both went this way.
- **A change request can rewrite another feature's rules** (the submission cap replaced co-speakers' "co-speaking
  doesn't count"). Mark the old rule as replaced in that feature's spec, so nobody tests for it again.
- **"Piling in together" means one lock for every way in.** When two operations count against the same limit, both
  take the same row lock; prove it with a mixed burst and the lock removed.

## The feedback

- **The API behind `/feedback` is the tool**: `/api/teams/<team>/feedback` has every check's status, the failing
  request and a **transcript of only the latest faulty run**, so read it the moment something turns red
  (`workflow/scripts/feedback.py`). The timeline (`…/timeline?check=…`) shows when a check failed.
- **Statuses**: `OK`, `FAULTY`, `None` (not run yet, or retired). Notes: `retired`; `2x` = the check runs at double
  load (shown in yellow), which costs nothing by itself.
- **A `None` answer in a transcript means the harness got no answer in time**, not that we answered wrongly. Check our
  own log before touching code: twice it showed we'd answered correctly in milliseconds, seconds after the harness
  gave up.
- **Our request log is half the evidence.** It records requests and errors but no successful bodies; when we needed
  those, we rebuilt the harness's data from its requests and checked our live answers against it.
- **Look for a pattern before debugging a one-off**: two unrelated failures both sat on a minute boundary, which
  explained them without a single code change.

## Deploys

- **Every deploy costs points**: the new instance starts cold and the switchover drops or delays requests. Bundle
  changes; push a fix at once only when a claimed check is red.
- **Halve the time a fix takes to go live**: build the image once with a layer cache and deploy the exact image the
  smoke gate tested (4–4.5 min → about 2 min from push to live). Make the JVM start fast on a small CPU (C1 only,
  serial GC: 38 s → 19 s) and warm it up before the harness arrives.
- **Gates that fail closed cost time, not points**: the first run of a new gate failed on its own setup, and nothing
  was deployed.
- **Run the whole suite before every push**, also for one-line fixes: a ratings-only run would have missed the SQL
  slip that broke every talk response.

## Late in the day

- **The harness raises the load in the afternoon** (bursts twice the size). Our instance stayed mostly idle while
  requests reached it seconds late; the logs pointed outside our container. We tried a cheap, reversible remedy
  (Tomcat keep-alive: unlimited requests per connection, 75 s idle), live 124 s after the push at 14:54. No failure
  since, and the switchover cost nothing, but the harness dropped "2x" at the same time, so we can't tell whether
  it helped. Lesson: when a fix and an outside change land together, say "inconclusive", not "fixed".
- **Decide on deploys by the bleed, not by the colour**: yellow `2x` markers on green checks cost nothing; only the
  bleed number tells you whether a deploy (which costs a switchover) is worth it.

## What we'd set up at 9 o'clock next time

1. Request logging, `feedback.py`, `harness-traffic.py` and the deploy gates, before the first feature.
2. `http-conventions.md` started from the first answers, and the rule "traffic before claim" from the first claim.
3. The fast pipeline and the start-up flags from the start, since every later fix rides on them.
4. A person who owns the product-owner questions; we wrote them down for every feature and, in the end, the harness
   answered them for us, at a few points each.
