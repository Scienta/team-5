# Programme export: our examples (rule ids E1-E12 from workflow/specs/programme-export.md).
# Run: hurl --test --variable base_url=http://localhost:8080 programme-export.hurl
#
# The programme is shared (the harness uses it too on the live service), so this only checks the rows of its own
# talks, in two rooms and slots it empties first.

DELETE {{base_url}}/programme/R3/S4
HTTP *
DELETE {{base_url}}/programme/R3/S5
HTTP *

# Setup: Grace's accepted workshop with Ada as co-speaker, and Ada's accepted talk with a comma and quotes in its title
POST {{base_url}}/speakers
{ "name": "Grace Hopper", "email": "export-grace-{{newUuid}}@example.com" }
HTTP 201
[Captures]
grace: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Ada Lovelace", "email": "export-ada-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ada: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Debugging, hands on", "format": "WORKSHOP", "level": "ADVANCED", "speakerId": {{grace}} }
HTTP 201
[Captures]
workshop: jsonpath "$.id"

PUT {{base_url}}/talks/{{workshop}}/co-speakers/{{ada}}
HTTP 200

POST {{base_url}}/talks
{ "title": "Kotlin, \"really\" fast", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{ada}} }
HTTP 201
[Captures]
kotlin: jsonpath "$.id"

PUT {{base_url}}/talks/{{workshop}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{workshop}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{workshop}}/status
{ "status": "ACCEPTED" }
HTTP 200
PUT {{base_url}}/talks/{{kotlin}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{kotlin}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{kotlin}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R3/S5
{ "talkId": {{kotlin}} }
HTTP 200
PUT {{base_url}}/programme/R3/S4
{ "talkId": {{workshop}} }
HTTP 200

# E1, E4, E6: the JSON rows, speakers main first then co-speakers
GET {{base_url}}/programme
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
jsonpath "$[?(@.talkId == {{workshop}})].slot" == "S4"
jsonpath "$[?(@.talkId == {{workshop}})].start" == "13:30"
jsonpath "$[?(@.talkId == {{workshop}})].end" == "15:30"
jsonpath "$[?(@.talkId == {{workshop}})].room" == "R3"
jsonpath "$[?(@.talkId == {{workshop}})].title" == "Debugging, hands on"
jsonpath "$[?(@.talkId == {{workshop}})].format" == "WORKSHOP"
jsonpath "$[?(@.talkId == {{workshop}})].speakers[0]" == "Grace Hopper"
jsonpath "$[?(@.talkId == {{workshop}})].speakers[1]" == "Ada Lovelace"
jsonpath "$[?(@.talkId == {{kotlin}})].slot" == "S5"

# E8-E11: text/csv, the exact header, our rows quoted only where they must be, speakers joined by "; "
GET {{base_url}}/programme.csv
HTTP 200
[Asserts]
header "Content-Type" == "text/csv;charset=UTF-8"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"
body contains "S4,13:30,15:30,R3,{{workshop}},\"Debugging, hands on\",WORKSHOP,Grace Hopper; Ada Lovelace\n"
body contains "S5,15:45,16:45,R3,{{kotlin}},\"Kotlin, \"\"really\"\" fast\",PRESENTATION,Ada Lovelace\n"
body endsWith "\n"
body not contains "\r"
body not endsWith "\n\n"

# E8: text/csv even when JSON is asked for
GET {{base_url}}/programme.csv
Accept: application/json
HTTP 200
[Asserts]
header "Content-Type" == "text/csv;charset=UTF-8"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"

# Clean up
DELETE {{base_url}}/programme/R3/S4
HTTP 204
DELETE {{base_url}}/programme/R3/S5
HTTP 204
