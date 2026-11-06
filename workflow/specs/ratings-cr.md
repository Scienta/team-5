# Ratings change request: our spec

Source: harness `/specs/ratings-cr/spec.md` (Version 1, change request released at tick 167, V: 2). No published
examples. Changes how `averageRating` is calculated (Ratings, `workflow/http-conventions.md` unaffected: no new
endpoints or status codes). At tick 167 the harness retired the two Ratings checks that tested the average and the
ranking (ratings/check-2 and check-4); the other three Ratings checks don't depend on the calculation, so the change
can go live before the claim without breaking a claimed check.

## Rules

| Id | Rule (from the change request) | Our decision |
|---|---|---|
| RC1 | 5 or more ratings: leave out one highest and one lowest score, exactly one of each even when several reviewers gave it. Fewer than 5: every score counts. | `(total - max - min) / (count - 2)` from the same `LATERAL` aggregate as the count (`MAX`, `MIN`), so still one query per list. |
| RC2 | Rounded to 2 decimals, ties to the even neighbour: `3.125` → `3.12`, `3.375` → `3.38`. | `BigDecimal` division of the integer total by the count with scale 2 and `HALF_EVEN`: exact, so a tie is a real tie. JSON writes the number as it is (`4.5`, `4.25`, `3.33`). |
| RC3 | `ratingCount` still counts every reviewer. | Unchanged. |
| RC4 | The ranking uses the new, rounded average. | Unchanged code: the ranking already sorts on `averageRating`, then `ratingCount`, then oldest. |

## Not changed

Everything else about ratings, and `/admin/stats` (not in any spec; its own rounding).

## Tests

`RatingsTest.cr_*` (trimming, duplicate top and bottom scores, banker's rounding at 3.125 and 3.375, a ranking that
trimming reorders) and `UtilsTest.averageRating`; `examples/ratings-cr.hurl`.

## What the harness tried (filled in after deploying, before claiming)

`python3 -I workflow/scripts/harness-traffic.py '/ratings/|sort=rating' --all`: about 300 ratings in 3 minutes, no errors.
Our log keeps no bodies for successful requests, so we rebuilt each talk's ratings from the traffic (the last score per
reviewer), computed the expected average per RC1/RC2 and compared it with `GET /talks/{id}` live: 135 talks, 18 with
5+ ratings, 0 mismatches. Claimed at tick 179.
