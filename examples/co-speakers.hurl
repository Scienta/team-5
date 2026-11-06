# Co-speakers: our examples (rule ids C1-C11 from workflow/specs/co-speakers.md).
# Run: hurl --test --variable base_url=http://localhost:8080 co-speakers.hurl

# Setup: a main speaker with a talk, and three other speakers
POST {{base_url}}/speakers
{ "name": "Main Speaker", "email": "main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co One", "email": "co1-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co1: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Two", "email": "co2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co2: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Three", "email": "co3-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co3: jsonpath "$.id"

# C7: a new talk shows an empty list
POST {{base_url}}/talks
{ "title": "Pair programming, live", "format": "WORKSHOP", "level": "INTERMEDIATE", "speakerId": {{main_id}} }
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.coSpeakerIds" count == 0

# C1, C5: add two, in order
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{talk_id}}
jsonpath "$.speakerId" == {{main_id}}
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{co1}}

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co2}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{co1}}
jsonpath "$.coSpeakerIds[1]" == {{co2}}

# C6: adding again changes nothing
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{co1}}

# C3: no third
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co3}}
HTTP 409

# C4: the main speaker can't co-speak their own talk
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{main_id}}
HTTP 400

# C7: shown wherever the talk is shown
GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2

GET {{base_url}}/speakers/{{main_id}}/talks
HTTP 200
[Asserts]
jsonpath "$[0].coSpeakerIds" count == 2

# C8: a co-speaker's own list has only their main talks
GET {{base_url}}/speakers/{{co1}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 0

# C2, C5: remove the first; added again, they go to the end
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{co2}}

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{co2}}
jsonpath "$.coSpeakerIds[1]" == {{co1}}

# C10: removing someone who isn't a co-speaker changes nothing
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{co3}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2

# C11: unknown talk or speaker
PUT {{base_url}}/talks/999999999/co-speakers/{{co1}}
HTTP 404

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/999999999
HTTP 404
