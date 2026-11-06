# Ratings change request: our examples (rule ids RC1-RC4 from workflow/specs/ratings-cr.md).
# Run: hurl --test --variable base_url=http://localhost:8080 ratings-cr.hurl

POST {{base_url}}/speakers
{ "name": "Outlier Owner", "email": "outlier-{{newUuid}}@example.com" }
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Trimmed means", "format": "PRESENTATION", "level": "ADVANCED", "speakerId": {{speaker}} }
HTTP 201
[Captures]
trimmed: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Plain means", "format": "PRESENTATION", "level": "ADVANCED", "speakerId": {{speaker}} }
HTTP 201
[Captures]
plain: jsonpath "$.id"

# RC1: 4 ratings, every score counts: 5, 5, 5, 4 -> 4.75
PUT {{base_url}}/talks/{{plain}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{plain}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{plain}}/ratings/3
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{plain}}/ratings/4
{ "score": 4 }
HTTP 200

GET {{base_url}}/talks/{{plain}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.75
jsonpath "$.ratingCount" == 4

# RC1, RC3: 5 ratings 5, 5, 5, 5, 1: one 5 and the 1 left out -> 5.0; all 5 still counted
PUT {{base_url}}/talks/{{trimmed}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{trimmed}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{trimmed}}/ratings/3
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{trimmed}}/ratings/4
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{trimmed}}/ratings/5
{ "score": 1 }
HTTP 200

GET {{base_url}}/talks/{{trimmed}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 5.0
jsonpath "$.ratingCount" == 5

# RC4: the ranking uses the new average (untrimmed, 4.2 would have come after 4.75)
GET {{base_url}}/talks
[Query]
sort: rating
speakerId: {{speaker}}
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].id" == {{trimmed}}
jsonpath "$[1].id" == {{plain}}

# RC2: 2 decimals, a sixth rating of 3: one 5 and the 1 left out of 5, 5, 5, 5, 1, 3 -> (5 + 5 + 5 + 3) / 4 = 4.5
PUT {{base_url}}/talks/{{trimmed}}/ratings/6
{ "score": 3 }
HTTP 200

GET {{base_url}}/talks/{{trimmed}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.5
jsonpath "$.ratingCount" == 6
