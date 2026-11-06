# Before claiming a feature

A claim is permanent, and we can't see a feature's checks until it's claimed. Tick every box, with the evidence in
the claim log (REVIEW.md section 3).

- [ ] Our spec `workflow/specs/<slug>.md` covers every rule of the feature request, each with an id.
- [ ] Every open decision is marked **(our guess)**, and a person has seen the list. Asked the product owner about
      the ones that matter (or decided consciously not to).
- [ ] A test per rule id, green on Java 25 (production). Concurrency rules: the test fails with the lock removed.
- [ ] `examples/<slug>.hurl` covers the happy path and each error case, green locally.
- [ ] `workflow/scripts/compare.sh` against `origin/main`: no differences except the feature's new fields.
- [ ] Pushed; the deploy's `tests` and `smoke` gates green; `feedback.py` shows the new version live and every
      claimed check OK.
- [ ] `examples/<slug>.hurl` green against `https://fagsem-h2026-team-5.scienta.cloud`.
- [ ] **Read the harness's traffic for the feature** (it runs the checks every minute, also before the claim):
      wait 2–3 ticks after the deploy, then `python3 -I workflow/scripts/harness-traffic.py '<path regex>'`.
      For every sequence it tries (twice in a row, wrong speaker, unknown ids, …), is our answer what the business
      would expect? Anything surprising: fix or ask the product owner first. A 5xx is always a bug.
- [ ] Claim (a person), then the claim log entry with the tick and this evidence.
- [ ] Watch `feedback.py` for the next few ticks; anything FAULTY goes into REVIEW.md section 4.
