# Acceptance state machine: our examples (rule ids A1-A12 from workflow/specs/acceptance.md).
# Run: hurl --test --variable base_url=http://localhost:8080 acceptance.hurl

# Setup: a speaker with two talks, and another speaker
POST {{base_url}}/speakers
{ "name": "Deciding Speaker", "email": "decide-{{newUuid}}@example.com" }
HTTP 201
[Captures]
speaker_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Someone Else", "email": "else-{{newUuid}}@example.com" }
HTTP 201
[Captures]
other_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "State machines in practice", "format": "WORKSHOP", "level": "ADVANCED", "speakerId": {{speaker_id}} }
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.status" == "SUBMITTED"

POST {{base_url}}/talks
{ "title": "Second thoughts", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{speaker_id}} }
HTTP 201
[Captures]
second_id: jsonpath "$.id"

# A4: accepting needs two different reviewers; one reviewer rating twice is one
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{talk_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{talk_id}}/ratings/1
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

# A1, A2: waitlist, then accept once a second reviewer has rated
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "WAITLISTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "WAITLISTED"

PUT {{base_url}}/talks/{{talk_id}}/ratings/2
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 200
[Asserts]
jsonpath "$.id" == {{talk_id}}
jsonpath "$.status" == "ACCEPTED"

# A3: final
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "REJECTED" }
HTTP 409

# A9: the status shows wherever the talk is shown
GET {{base_url}}/speakers/{{speaker_id}}/talks
HTTP 200
[Asserts]
jsonpath "$[0].status" == "ACCEPTED"
jsonpath "$[1].status" == "SUBMITTED"

# A7: nobody else can withdraw (403 is our guess)
POST {{base_url}}/talks/{{talk_id}}/withdraw
{ "speakerId": {{other_id}} }
HTTP 403

# A6: the speaker withdraws an accepted talk; A8: then it's out for good
POST {{base_url}}/talks/{{talk_id}}/withdraw
{ "speakerId": {{speaker_id}} }
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

POST {{base_url}}/talks/{{talk_id}}/withdraw
{ "speakerId": {{speaker_id}} }
HTTP 409

PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "WAITLISTED" }
HTTP 409

# A5, A6: rejecting needs no ratings; a rejected talk can't be withdrawn
PUT {{base_url}}/talks/{{second_id}}/status
{ "status": "REJECTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "REJECTED"

POST {{base_url}}/talks/{{second_id}}/withdraw
{ "speakerId": {{speaker_id}} }
HTTP 409

# A12: bad input and unknown talks
PUT {{base_url}}/talks/{{second_id}}/status
{ "status": "SUBMITTED" }
HTTP 400

PUT {{base_url}}/talks/{{second_id}}/status
{ "status": "accepted" }
HTTP 400

POST {{base_url}}/talks/{{second_id}}/withdraw
{ }
HTTP 400

PUT {{base_url}}/talks/999999999/status
{ "status": "REJECTED" }
HTTP 404
