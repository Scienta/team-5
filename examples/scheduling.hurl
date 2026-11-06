# Programme scheduling: our examples (rule ids P1-P14 from workflow/specs/scheduling.md).
# Run: hurl --test --variable base_url=http://localhost:8080 scheduling.hurl
#
# The programme is shared (15 rooms and slots, and the harness uses them too on the live service), so this empties
# the ones it uses first and accepts any answer for that.

DELETE {{base_url}}/programme/R1/S4
HTTP *
DELETE {{base_url}}/programme/R2/S4
HTTP *
DELETE {{base_url}}/programme/R1/S5
HTTP *
DELETE {{base_url}}/programme/R2/S5
HTTP *

# Setup: Ada with an accepted workshop and an accepted presentation, Grace with an accepted lightning talk that has
# Ada as co-speaker, and a talk nobody has decided on
POST {{base_url}}/speakers
{ "name": "Ada Lovelace", "email": "sched-ada-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ada: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Grace Hopper", "email": "sched-grace-{{newUuid}}@example.com" }
HTTP 201
[Captures]
grace: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Engines, hands on", "format": "WORKSHOP", "level": "ADVANCED", "speakerId": {{ada}} }
HTTP 201
[Captures]
workshop: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Notes on the engine", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{ada}} }
HTTP 201
[Captures]
presentation: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Bugs, literally", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{grace}} }
HTTP 201
[Captures]
lightning: jsonpath "$.id"

PUT {{base_url}}/talks/{{lightning}}/co-speakers/{{ada}}
HTTP 200

POST {{base_url}}/talks
{ "title": "Undecided", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{grace}} }
HTTP 201
[Captures]
undecided: jsonpath "$.id"

PUT {{base_url}}/talks/{{workshop}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{workshop}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{workshop}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/talks/{{presentation}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{presentation}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{presentation}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/talks/{{lightning}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{lightning}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{lightning}}/status
{ "status": "ACCEPTED" }
HTTP 200

# P7: a workshop needs the long slot (guess: 409 elsewhere)
PUT {{base_url}}/programme/R1/S5
{ "talkId": {{workshop}} }
HTTP 409

# P2, P3: put it in S4, with the times as on the signs
PUT {{base_url}}/programme/R1/S4
{ "talkId": {{workshop}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R1"
jsonpath "$.slot" == "S4"
jsonpath "$.start" == "13:30"
jsonpath "$.end" == "15:30"
jsonpath "$.talkId" == {{workshop}}

# P4: look it up
GET {{base_url}}/programme/R1/S4
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{workshop}}
jsonpath "$.start" == "13:30"

# P9: a talk is on the programme only once
PUT {{base_url}}/programme/R2/S4
{ "talkId": {{workshop}} }
HTTP 409

# P10: Ada can't be in R2 while she gives the workshop in R1, as main speaker or as co-speaker
PUT {{base_url}}/programme/R2/S4
{ "talkId": {{presentation}} }
HTTP 409

PUT {{base_url}}/programme/R2/S4
{ "talkId": {{lightning}} }
HTTP 409

# P8: one talk per room and slot
PUT {{base_url}}/programme/R1/S4
{ "talkId": {{presentation}} }
HTTP 409

# P7: everything else fits anywhere; P10: another slot is fine
PUT {{base_url}}/programme/R1/S5
{ "talkId": {{presentation}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "15:45"
jsonpath "$.end" == "16:45"

# P6: only accepted talks
PUT {{base_url}}/programme/R2/S5
{ "talkId": {{undecided}} }
HTTP 409

# P5: take the workshop out (guess: 204), then the room is empty and the talk can go elsewhere
DELETE {{base_url}}/programme/R1/S4
HTTP 204

GET {{base_url}}/programme/R1/S4
HTTP 404

# P5: taking out of an empty room and slot is 404
DELETE {{base_url}}/programme/R1/S4
HTTP 404

PUT {{base_url}}/programme/R2/S4
{ "talkId": {{workshop}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R2"

# P1: only R1-R3 and S1-S5, exact capitals (guess: 404)
PUT {{base_url}}/programme/R4/S1
{ "talkId": {{presentation}} }
HTTP 404

GET {{base_url}}/programme/r1/S5
HTTP 404

DELETE {{base_url}}/programme/R1/S6
HTTP 404

# P13: bad input
PUT {{base_url}}/programme/R3/S1
{ "talkId": "one" }
HTTP 400

PUT {{base_url}}/programme/R3/S1
{ }
HTTP 400

# P14: an unknown talk is bad input, 400 (404 failed the harness's check-1 at tick 131)
PUT {{base_url}}/programme/R3/S1
{ "talkId": 999999999 }
HTTP 400

# Clean up
DELETE {{base_url}}/programme/R2/S4
HTTP 204
DELETE {{base_url}}/programme/R1/S5
HTTP 204
