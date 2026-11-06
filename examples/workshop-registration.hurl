# Workshop registration: our examples (rule ids W1-W11 from workflow/specs/workshop-registration.md).
# Run: hurl --test --variable base_url=http://localhost:8080 workshop-registration.hurl

# Setup: an accepted workshop (two reviewers), a workshop that isn't accepted, and an accepted presentation
POST {{base_url}}/speakers
{ "name": "Workshop Host", "email": "host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Build a CFP in a day", "format": "WORKSHOP", "level": "INTERMEDIATE", "speakerId": {{host}} }
HTTP 201
[Captures]
workshop: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Not decided yet", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{host}} }
HTTP 201
[Captures]
undecided: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Just a talk", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host}} }
HTTP 201
[Captures]
presentation: jsonpath "$.id"

PUT {{base_url}}/talks/{{workshop}}/ratings/1
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{workshop}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{workshop}}/status
{ "status": "ACCEPTED" }
HTTP 200

# W1: register; the email comes back as typed
POST {{base_url}}/workshops/{{workshop}}/registrations
{ "name": "Ada Lovelace", "email": "Ada-{{newUuid}}@Example.com" }
HTTP 201
[Captures]
ada_email: jsonpath "$.email"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.workshopId" == {{workshop}}
jsonpath "$.name" == "Ada Lovelace"
jsonpath "$.email" startsWith "Ada-"

POST {{base_url}}/workshops/{{workshop}}/registrations
{ "name": "Grace Hopper", "email": "grace-{{newUuid}}@example.com" }
HTTP 201

# W8: the same email in other letters is the same person (409 is our guess)
POST {{base_url}}/workshops/{{workshop}}/registrations
{ "name": "Ada Again", "email": "{{ada_email}}" }
HTTP 409

# W10: the list, in the order they registered
GET {{base_url}}/workshops/{{workshop}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].name" == "Ada Lovelace"
jsonpath "$[0].email" == "{{ada_email}}"
jsonpath "$[1].name" == "Grace Hopper"

# W1: both fields required
POST {{base_url}}/workshops/{{workshop}}/registrations
{ "name": "No Email" }
HTTP 400

POST {{base_url}}/workshops/{{workshop}}/registrations
{ "email": "nameless-{{newUuid}}@example.com" }
HTTP 400

# W4: only accepted workshops (409 is our guess)
POST {{base_url}}/workshops/{{undecided}}/registrations
{ "name": "Too Early", "email": "early-{{newUuid}}@example.com" }
HTTP 409

# W3: only workshops: 409, like a workshop that isn't accepted (what the harness expects, tick 119)
POST {{base_url}}/workshops/{{presentation}}/registrations
{ "name": "Wrong Room", "email": "wrong-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{presentation}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 0

POST {{base_url}}/workshops/999999999/registrations
{ "name": "Nowhere", "email": "nowhere-{{newUuid}}@example.com" }
HTTP 404

# W5: withdrawn later, the list stays
POST {{base_url}}/talks/{{workshop}}/withdraw
{ "speakerId": {{host}} }
HTTP 200

GET {{base_url}}/workshops/{{workshop}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 2

POST {{base_url}}/workshops/{{workshop}}/registrations
{ "name": "Too Late", "email": "late-{{newUuid}}@example.com" }
HTTP 409
