# Decisions the feature request leaves open

A feature request gives business rules and an API, not status codes or edge cases. The harness checks them anyway,
and we only see its checks after a person claims (claims are permanent). Learned the hard way at tick 85
(REVIEW.md section 4: we chose 200 for removing a co-speaker who wasn't one; the harness expected 404).

- Write every rule and every open decision in `workflow/specs/<slug>.md` with an id (C1, C2, …) before writing
  code. Mark each decision the request doesn't state as **(our guess)** with one line of why.
- Default to the conventional HTTP answer, not to cleverness:
  - the resource or relation in the path doesn't exist: **404**, also for `DELETE` (removing what isn't there is
    not success);
  - the request is malformed or breaks a rule about the input itself: **400**;
  - the request is valid but conflicts with the current state (a limit reached, a transition not allowed): **409**;
  - "changes nothing" in the request (e.g. adding twice) means 200 with the unchanged resource, but only where the
    request says so. Don't extend it by analogy to other operations.
- When the request puts several conditions in one sentence ("only workshops that the committee has accepted take
  registrations"), they are one rule: give every way of breaking it the same answer. Don't split it into "not that
  kind of resource" (404) and "wrong state" (409). Learned at tick 119: the harness accepted our 409 for a waitlisted
  workshop and failed our 404 for an accepted non-workshop, in the same check.
- 404 is for ids that don't exist. A resource that exists but doesn't qualify for the operation is a 409 (or 400 if
  it's the input itself that's wrong).
- Check order: path resources first (404), then the body (400), then state (409).
- Finish by listing the guesses for the person driving, so they can ask the product owner before the claim. Don't
  call a feature ready while a guess is unconfirmed; say which ones are open.
- Tests and Hurl examples for a guess are labelled as such (`// guess: …`), so nobody mistakes them for the spec.
