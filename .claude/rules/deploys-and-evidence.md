# Deploys and evidence

- Push to `main` deploys to the scored service. Every deploy costs a few points while the new instance starts cold
  (REVIEW.md section 4, tick 47), so bundle changes; don't push one small fix at a time unless a claimed check is
  FAULTY (then push the fix at once).
- Before saying a change is safe: the full test suite on Java 11 (`JAVA_HOME=~/.jdks/ms-11.0.33`), every
  `examples/*.hurl`, and for anything that shouldn't change behaviour `workflow/scripts/compare.sh` (0 differences,
  or only fields the feature adds, via `IGNORE_FIELDS`). Report the numbers, not "should be fine".
- A concurrency test counts only after it has been seen to fail with the lock removed.
- After a deploy: `python3 -I workflow/scripts/feedback.py` (version = origin/main, every check OK, bleed unchanged).
  If a check is FAULTY, read its transcript straight away (only the latest faulty run is kept) and the request log.
- Write each claim, each FAULTY check and its cause into REVIEW.md (sections 3 and 4) as it happens.
- Never print or commit `.fagdag/harness-token`.
