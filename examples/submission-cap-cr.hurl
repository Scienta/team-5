# Submission cap change request: our examples (rule ids SC1-SC8 from workflow/specs/submission-cap-cr.md).
# Run: hurl --test --variable base_url=http://localhost:8080 submission-cap-cr.hurl
# Fresh speakers and talks each run (random emails), so it's safe against the shared live service.

# A speaker and three others whose talks we'll co-speak on
POST {{base_url}}/speakers
{ "name": "Capped", "email": "cap-{{newUuid}}@example.com" }
HTTP 201
[Captures]
s: jsonpath "$.id"

# SC3: three of their own in-the-running talks, then the fourth is refused
POST {{base_url}}/talks
{ "title": "One", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201
[Captures]
t1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Two", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "Three", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "Four", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 409

# SC2 / SC6: reject the first, a place frees straight away
PUT {{base_url}}/talks/{{t1}}/status
{ "status": "REJECTED" }
HTTP 200

POST {{base_url}}/talks
{ "title": "Five", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

# SC1 / SC4: co-speaking counts. A fresh speaker co-speaks on three in-the-running talks, then can't submit or co-speak again.
POST {{base_url}}/speakers
{ "name": "Co", "email": "co-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co: jsonpath "$.id"

# Each host talk gets its own owner, since the owner's own cap counts too.
POST {{base_url}}/speakers
{ "name": "Owner1", "email": "owner1-{{newUuid}}@example.com" }
HTTP 201
[Captures]
o1: jsonpath "$.id"
POST {{base_url}}/talks
{ "title": "H1", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{o1}} }
HTTP 201
[Captures]
h1: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Owner2", "email": "owner2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
o2: jsonpath "$.id"
POST {{base_url}}/talks
{ "title": "H2", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{o2}} }
HTTP 201
[Captures]
h2: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Owner3", "email": "owner3-{{newUuid}}@example.com" }
HTTP 201
[Captures]
o3: jsonpath "$.id"
POST {{base_url}}/talks
{ "title": "H3", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{o3}} }
HTTP 201
[Captures]
h3: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Owner4", "email": "owner4-{{newUuid}}@example.com" }
HTTP 201
[Captures]
o4: jsonpath "$.id"
POST {{base_url}}/talks
{ "title": "H4", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{o4}} }
HTTP 201
[Captures]
h4: jsonpath "$.id"

PUT {{base_url}}/talks/{{h1}}/co-speakers/{{co}}
HTTP 200
PUT {{base_url}}/talks/{{h2}}/co-speakers/{{co}}
HTTP 200
PUT {{base_url}}/talks/{{h3}}/co-speakers/{{co}}
HTTP 200

# at the cap through co-speaking: a new submission and a new in-the-running co-speaker add are both refused
POST {{base_url}}/talks
{ "title": "Mine", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{co}} }
HTTP 409

PUT {{base_url}}/talks/{{h4}}/co-speakers/{{co}}
HTTP 409

# SC6: taking them off one talk frees a place straight away
DELETE {{base_url}}/talks/{{h3}}/co-speakers/{{co}}
HTTP 200

PUT {{base_url}}/talks/{{h4}}/co-speakers/{{co}}
HTTP 200

# SC5: a rejected talk doesn't count, so even at the cap they can be added to it
PUT {{base_url}}/talks/{{h1}}/status
{ "status": "REJECTED" }
HTTP 200

# co is now at the cap again (h2, h4, and... h1 rejected frees one -> at 2). Fill back to 3, then show SC5.
POST {{base_url}}/talks
{ "title": "CoOwn", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{co}} }
HTTP 201

# now co has h2, h4, CoOwn = 3 in the running; h1 is rejected
PUT {{base_url}}/talks/{{h1}}/co-speakers/{{co}}
HTTP 200
