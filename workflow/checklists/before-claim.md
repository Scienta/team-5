# Before claiming a feature

A claim is permanent, and we can't see a feature's checks until it's claimed. Tick every box, with the evidence in
the claim log (REVIEW.md section 3).

- [ ] Our spec `workflow/specs/<slug>.md` covers every rule of the feature request, each with an id.
- [ ] Every open decision is marked **(our guess)**, and a person has seen the list. Asked the product owner about
      the ones that matter (or decided consciously not to).
- [ ] A test per rule id, green on Java 11. Concurrency rules: the test fails with the lock removed.
- [ ] `examples/<slug>.hurl` covers the happy path and each error case, green locally.
- [ ] `workflow/scripts/compare.sh` against `origin/main`: no differences except the feature's new fields.
- [ ] Pushed; the deploy's `tests` and `smoke` gates green; `feedback.py` shows the new version live and every
      claimed check OK.
- [ ] `examples/<slug>.hurl` green against `https://fagsem-h2026-team-5.scienta.cloud`.
- [ ] Claim (a person), then the claim log entry with the tick and this evidence.
- [ ] Watch `feedback.py` for the next few ticks; anything FAULTY goes into REVIEW.md section 4.
