#!/usr/bin/env bash
# Sends a fixed set of requests from the base specs (speakers, talks, ratings) to a running service and prints every
# status and response body, so two builds can be diffed for "nothing changes for our users":
#
#   workflow/api-snapshot.sh http://localhost:8080 > before.txt   # old build, empty database
#   workflow/api-snapshot.sh http://localhost:8081 > after.txt    # new build, another empty database
#   diff before.txt after.txt
#
# Run each against an EMPTY database: ids are compared as-is. Digits inside timestamps are replaced with 9, so the
# format is compared but not the time itself.
set -euo pipefail
base="${1:-http://localhost:8080}"

req() {
    local method="$1" path="$2" body="${3-}" type="${4:-application/json}"
    local args=(-sS -o /tmp/api-snapshot-body -w '%{http_code} %{content_type}' -X "$method")
    if [ -n "$body" ]; then
        args+=(-H "Content-Type: $type" --data-binary "$body")
    fi
    echo "### $method $path ${body:0:120}"
    curl "${args[@]}" "$base$path"
    echo
    # Timestamps: keep the shape, drop the value.
    perl -pe 's/("(?:timestamp|submittedAt|submittedOn|ratedAtDisplay)"\s*:\s*")([^"]*)"/my ($k,$v)=($1,$2); $v=~s|\d|9|g; "$k$v\""/ge' \
        /tmp/api-snapshot-body
    echo
}

long_bio=$(printf 'x%.0s' $(seq 1 501))
ok_bio=$(printf 'x%.0s' $(seq 1 500))

# ---- speakers
req POST /speakers '{"name":"Ada Lovelace","email":"ada@example.com","bio":"Analyst"}'
req POST /speakers/ '{"name":"Grace Hopper","email":"grace@example.com"}'
req POST /speakers '{"name":"Ada Again","email":"ADA@Example.com"}'
req POST /speakers '{"name":"","email":"blank@example.com"}'
req POST /speakers '{"name":"   ","email":"spaces@example.com"}'
req POST /speakers '{"email":"noname@example.com"}'
req POST /speakers '{"name":"No Email"}'
req POST /speakers '{"name":"Bad Email","email":"not-an-email"}'
req POST /speakers "{\"name\":\"Long Bio\",\"email\":\"long@example.com\",\"bio\":\"$long_bio\"}"
req POST /speakers "{\"name\":\"Max Bio\",\"email\":\"max@example.com\",\"bio\":\"$ok_bio\"}"
req POST /speakers '{"name":"Null Bio","email":"nullbio@example.com","bio":null}'
req POST /speakers '{"name":"Extra","email":"extra@example.com","twitter":"@x"}'
req POST /speakers '{"name":"Commented" /* legacy client */,"email":"comment@example.com"}'
req POST /speakers '{"name":"Broken",'
req POST /speakers ''
req POST /speakers 'name=Form' 'text/plain'
req POST /speakers '[1,2]'
req GET /speakers/1
req GET /speakers/1/
req GET /speakers/2
req GET /speakers/999
req GET /speakers/abc
req GET /speakers/0
req GET /speakers/-1

# ---- talks
req POST /talks '{"title":"Legacy is a feeling","format":"PRESENTATION","level":"INTERMEDIATE","speakerId":1}'
req POST /talks '{"title":"Lightning","format":"LIGHTNING_10","level":"BEGINNER","speakerId":1}'
req POST /talks/ '{"title":"Workshop","format":"WORKSHOP","level":"ADVANCED","speakerId":1,"durationMinutes":5}'
req POST /talks '{"title":"Fourth","format":"LIGHTNING_20","level":"BEGINNER","speakerId":1}'
req POST /talks '{"title":"Twenty","format":"LIGHTNING_20","level":"BEGINNER","speakerId":2}'
req POST /talks '{"title":"Lower","format":"presentation","level":"BEGINNER","speakerId":2}'
req POST /talks '{"title":"Keynote","format":"KEYNOTE","level":"BEGINNER","speakerId":2}'
req POST /talks '{"title":"Level","format":"PRESENTATION","level":"EXPERT","speakerId":2}'
req POST /talks '{"title":"","format":"PRESENTATION","level":"BEGINNER","speakerId":2}'
req POST /talks '{"format":"PRESENTATION","level":"BEGINNER","speakerId":2}'
req POST /talks '{"title":"Nobody","format":"PRESENTATION","level":"BEGINNER","speakerId":999}'
req POST /talks '{"title":"No speaker","format":"PRESENTATION","level":"BEGINNER"}'
req POST /talks '{"title":"String id","format":"PRESENTATION","level":"BEGINNER","speakerId":"2"}'
req POST /talks '{"title":"Bad id","format":"PRESENTATION","level":"BEGINNER","speakerId":"two"}'
req GET /talks/1
req GET /talks/1/
req GET /talks/999
req GET /talks/abc
req GET /speakers/1/talks
req GET /speakers/1/talks/
req GET /speakers/2/talks
req GET /speakers/999/talks

# ---- ratings
req PUT /talks/1/ratings/1 '{"score":4}'
req PUT /talks/1/ratings/2 '{"score":4}'
req PUT /talks/1/ratings/3 '{"score":4}'
req PUT /talks/1/ratings/4 '{"score":5}'
req PUT /talks/1/ratings/4 '{"score":5}'
req PUT /talks/2/ratings/1 '{"score":5}'
req PUT /talks/2/ratings/1 '{"score":3}'
req PUT /talks/4/ratings/9 '{"score":4}'
req PUT /talks/4/ratings/8 '{"score":5}'
req PUT /talks/1/ratings/5 '{"score":0}'
req PUT /talks/1/ratings/5 '{"score":6}'
req PUT /talks/1/ratings/5 '{"score":"4"}'
req PUT /talks/1/ratings/5 '{"score":4.5}'
req PUT /talks/1/ratings/5 '{"score":4.0}'
req PUT /talks/1/ratings/5 '{"score":null}'
req PUT /talks/1/ratings/5 '{}'
req PUT /talks/1/ratings/0 '{"score":3}'
req PUT /talks/1/ratings/-1 '{"score":3}'
req PUT /talks/1/ratings/abc '{"score":3}'
req PUT /talks/999/ratings/1 '{"score":3}'
req PUT /talks/1/ratings/5 ''
req GET /talks/1
req GET /talks/2
req GET /talks
req GET /talks/
req GET '/talks?sort=rating'
req GET '/talks?speakerId=1'
req GET '/talks?speakerId=1&sort=rating'
req GET '/talks?speakerId=999'
req GET '/talks?sort=title'
req GET '/talks?speakerId=abc'

# ---- the rest of the surface
req GET /health
req GET /actuator/health
req GET /nowhere
req DELETE /speakers/1
req PATCH /talks/1 '{}'
