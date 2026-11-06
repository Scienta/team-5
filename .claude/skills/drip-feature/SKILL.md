---
name: drip-feature
description: Take a fagdag drip feature from the harness's feature request to a claim-ready, deployed implementation with evidence. Use when the user asks to start, build or implement a feature from the harness roadmap (co-speakers, acceptance, workshop-registration, scheduling, ...).
---

# From feature request to claim

The team's workflow, learned the hard way (REVIEW.md sections 1 and 4). Follow it in order and report evidence as
numbers. A person claims; never say a feature is done without evidence they can check.

1. **Read the request**: `curl -fsS -H "Authorization: Bearer $(tr -d '[:space:]' < .fagdag/harness-token)"
   "$(cat .fagdag/harness-url)/specs/<slug>/spec.md"`, and try `/specs/<slug>/examples.hurl` (404 = none). Read the
   specs it links to. Never print the token.
2. **Write our spec** from `workflow/specs/TEMPLATE.md` into `workflow/specs/<slug>.md`: a rule id per sentence, then
   every open decision, decided by `.claude/rules/spec-decisions.md` and marked **(our guess)**. List the product-owner
   questions, behaviour-changing ones first.
3. **Implement** next to the existing code (a service per feature, `ResponseStatusException` for errors). Anything
   with "at once", "rush" or "several people": lock the row (`SELECT … FOR UPDATE`) and check the rules on the locked
   row; back uniqueness with a unique index.
4. **Tests per rule id** (`<Feature>Test`, real app, Testcontainers): `workflow/scripts/mvn25.sh test
   -Dtest=<Feature>Test` if there's no local JDK 25. Then the **mutation check**: remove the `FOR UPDATE`, run the
   concurrency tests, see them fail, put it back. Report what failed (e.g. "22 in 20 places").
5. **Hurl example** `examples/<slug>.hurl`: happy path and every error case, marked where it tests a guess.
6. **Regression**: full suite (`workflow/scripts/mvn25.sh package`), every Hurl file against the new build, and
   `compare.py` between the live code and the new build (Docker images if needed): 0 differences, or only fields the
   feature adds (`IGNORE_FIELDS`).
7. **Commit** with the evidence in the message; the person pushes (push = deploy, every deploy costs a few points
   while the instance is cold, so bundle).
8. **After the deploy**: `python3 -I workflow/scripts/feedback.py` (version live, every claimed check OK), the Hurl
   file against `https://fagsem-h2026-team-5.scienta.cloud`, then wait 2–3 ticks and read
   `python3 -I workflow/scripts/harness-traffic.py '<path regex>' --all`. Go through every sequence the checks try;
   flag any answer the request doesn't clearly support, and any 5xx. Fill in the spec's last section.
9. **Recommend** claim or fix, with the evidence, using `workflow/checklists/before-claim.md`. Optionally get an
   independent read from the `spec-reviewer` agent first.
10. **After the claim**: the claim log entry in REVIEW.md section 3; watch `feedback.py`; any FAULTY check: read its
    transcript at once (only the latest is kept), fix, and write it up in section 4.
