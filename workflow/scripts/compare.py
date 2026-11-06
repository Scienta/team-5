"""Differential test: send the same ~80 requests to two running versions of the service and compare status, key
headers and normalised bodies (ids, timestamps and unique test data are masked).

    python3 -I workflow/scripts/compare.py http://localhost:8082 http://localhost:8081

Usually run through compare.sh, which builds and starts both versions. IGNORE_FIELDS=a,b leaves fields a new feature
adds on purpose out of the comparison (report them separately). Exit code 1 if anything differs.
The two servers may share a database: every run uses its own unique emails and names.
Covers the Speakers, Talk submissions and Ratings specs (happy paths and error cases). Add new features' requests
at the end of script() once both versions have them.
"""
import json, os, re, sys, urllib.request, urllib.error, uuid

OLD, NEW = sys.argv[1], sys.argv[2]
# Fields a new feature adds on purpose, left out of the comparison: IGNORE_FIELDS=coSpeakerIds,other
IGNORE = [f for f in os.environ.get("IGNORE_FIELDS", "").split(",") if f]


def call(base, method, path, body=None, ctype="application/json"):
    data = body.encode() if isinstance(body, str) else None
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None and ctype:
        req.add_header("Content-Type", ctype)
    try:
        r = urllib.request.urlopen(req)
    except urllib.error.HTTPError as e:
        r = e
    raw = r.read().decode("utf-8", "replace")
    hdr = {k: r.headers.get(k) for k in ("Content-Type", "Content-Disposition", "Allow")}
    if hdr["Allow"]:  # the method order varies from one start to the next
        hdr["Allow"] = ", ".join(sorted(m.strip() for m in hdr["Allow"].split(",")))
    hdr["framing"] = "length" if r.headers.get("Content-Length") else ("chunked" if r.headers.get("Transfer-Encoding") else "none")
    return r.status, hdr, raw


def script(base):
    """Yields (label, status, headers, body). Uses its own unique emails so both servers can share the DB."""
    tag = uuid.uuid4().hex[:8]
    ids = {}
    out = []

    def go(label, method, path, body=None, ctype="application/json"):
        for k, v in ids.items():
            path = path.replace("{" + k + "}", str(v))
            if body:
                body = body.replace("{" + k + "}", str(v))
        s, h, b = call(base, method, path, body, ctype)
        out.append((label, s, h, b))
        return s, b

    def em(name):
        return f"{name}-{tag}@example.com"

    def speaker(label, name, email, bio=None, path="/speakers", key=None):
        d = {"name": name, "email": email}
        if bio is not None:
            d["bio"] = bio
        s, b = go(label, "POST", path, json.dumps(d))
        if key and s == 201:
            ids[key] = json.loads(b)["id"]

    # --- speakers
    speaker("register with bio", "Ada", em("ada"), "Wrote the first program.", key="ada")
    speaker("register no bio", "Grace", em("grace"), key="grace")
    speaker("register trailing slash", "Tim", em("tim"), path="/speakers/", key="tim")
    speaker("register plus tag + caps", f"Plus{tag}", f"Plus+cfp-{tag}@Example.CO.uk", key="plus")
    speaker("dup email", "Ada2", em("ada"))
    speaker("dup email upper", "Ada3", em("ada").upper())
    speaker("blank name", "  ", em("blank"))
    go("missing email", "POST", "/speakers", json.dumps({"name": "X"}))
    go("missing name", "POST", "/speakers", json.dumps({"email": em("noname")}))
    speaker("bio 500", "B5", em("b500"), "x" * 500, key="b500")
    speaker("bio 501", "B6", em("b501"), "x" * 501)
    go("name as number", "POST", "/speakers", json.dumps({"name": 123, "email": em("num")}))
    go("bad json", "POST", "/speakers", "{bad")
    go("empty body", "POST", "/speakers", "")
    go("text/plain", "POST", "/speakers", json.dumps({"name": "P", "email": em("plain")}), "text/plain")
    go("json with comment", "POST", "/speakers", '{/*c*/"name":"C","email":"' + em("comment") + '"}')
    go("unknown field", "POST", "/speakers", json.dumps({"name": "U", "email": em("unk"), "twitter": "@u"}))
    for bad in ["not-an-email", f"a-{tag}@b", "@example.com", f"sp ace-{tag}@example.com", f"dot-{tag}@example.com."]:
        speaker(f"invalid email {bad.replace(tag, 'TAG')}", "E", bad)
    go("get", "GET", "/speakers/{ada}")
    go("get trailing slash", "GET", "/speakers/{grace}/")
    go("get missing", "GET", "/speakers/999999999")
    go("get abc", "GET", "/speakers/abc")
    go("get speakers list (no GET mapping)", "GET", "/speakers")
    go("delete speaker (405)", "DELETE", "/speakers/{ada}")
    go("put speaker ok", "PUT", "/speakers/{grace}", json.dumps({"name": "Grace H", "email": em("grace"), "bio": "b"}))
    go("put speaker bad email", "PUT", "/speakers/{grace}", json.dumps({"name": "Grace H", "email": "nope", "bio": "b"}))
    go("put speaker taken", "PUT", "/speakers/{grace}", json.dumps({"name": "G", "email": em("ada")}))
    go("search", "GET", f"/speakers/search?q=plus{tag}")

    # --- talks
    def talk(label, title, fmt, level, sid, key=None):
        d = {"title": title, "format": fmt, "level": level, "speakerId": sid}
        s, b = go(label, "POST", "/talks", json.dumps(d))
        if key and s == 201:
            ids[key] = json.loads(b)["id"]

    talk("talk workshop", "W", "WORKSHOP", "BEGINNER", ids["ada"], key="t1")
    talk("talk lightning10", "L10", "LIGHTNING_10", "ADVANCED", ids["ada"], key="t2")
    talk("talk presentation", "P", "PRESENTATION", "INTERMEDIATE", ids["ada"], key="t3")
    talk("talk 4th -> limit", "X", "LIGHTNING_20", "BEGINNER", ids["ada"])
    talk("talk lightning20", "L20", "LIGHTNING_20", "BEGINNER", ids["grace"], key="t4")
    talk("talk blank title", " ", "WORKSHOP", "BEGINNER", ids["grace"])
    talk("talk lowercase format", "x", "workshop", "BEGINNER", ids["grace"])
    talk("talk keynote", "x", "KEYNOTE", "BEGINNER", ids["grace"])
    talk("talk bad level", "x", "WORKSHOP", "EXPERT", ids["grace"])
    talk("talk unknown speaker", "x", "WORKSHOP", "BEGINNER", 999999999)
    go("talk no speaker", "POST", "/talks", json.dumps({"title": "x", "format": "WORKSHOP", "level": "BEGINNER"}))
    go("talk with duration", "POST", "/talks", json.dumps({"title": "D", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": ids["tim"], "durationMinutes": 5}))
    go("get talk", "GET", "/talks/{t1}")
    go("get talk trailing", "GET", "/talks/{t1}/")
    go("get talk missing", "GET", "/talks/999999999")
    go("speaker talks", "GET", "/speakers/{ada}/talks")
    go("speaker talks trailing", "GET", "/speakers/{ada}/talks/")
    go("speaker talks missing", "GET", "/speakers/999999999/talks")

    # --- ratings
    go("rate 4", "PUT", "/talks/{t1}/ratings/1", '{"score":4}')
    go("rate again 4", "PUT", "/talks/{t1}/ratings/2", '{"score":4}')
    go("rate 4 r3", "PUT", "/talks/{t1}/ratings/3", '{"score":4}')
    go("rate 5", "PUT", "/talks/{t1}/ratings/4", '{"score":5}')
    go("rate replace", "PUT", "/talks/{t2}/ratings/1", '{"score":2}')
    go("rate replace 2", "PUT", "/talks/{t2}/ratings/1", '{"score":3}')
    go("rate 0", "PUT", "/talks/{t1}/ratings/1", '{"score":0}')
    go("rate 6", "PUT", "/talks/{t1}/ratings/1", '{"score":6}')
    go("rate 3.0", "PUT", "/talks/{t1}/ratings/1", '{"score":3.0}')
    go("rate 3.5", "PUT", "/talks/{t1}/ratings/1", '{"score":3.5}')
    go("rate string", "PUT", "/talks/{t1}/ratings/1", '{"score":"3"}')
    go("rate null", "PUT", "/talks/{t1}/ratings/1", '{"score":null}')
    go("rate empty obj", "PUT", "/talks/{t1}/ratings/1", '{}')
    go("rate no body", "PUT", "/talks/{t1}/ratings/1", None)
    go("rate reviewer 0", "PUT", "/talks/{t1}/ratings/0", '{"score":3}')
    go("rate reviewer -1", "PUT", "/talks/{t1}/ratings/-1", '{"score":3}')
    go("rate reviewer abc", "PUT", "/talks/{t1}/ratings/abc", '{"score":3}')
    go("rate missing talk", "PUT", "/talks/999999999/ratings/1", '{"score":3}')
    go("talk after ratings", "GET", "/talks/{t1}")
    go("list by speaker", "GET", "/talks?speakerId={ada}")
    go("rank by speaker", "GET", "/talks?sort=rating&speakerId={ada}")
    go("sort foo", "GET", "/talks?sort=foo")
    go("list unknown speaker", "GET", "/talks?speakerId=999999999")

    # --- ranking with ties (Ratings spec): same rounded average, more reviewers first, then oldest; unrated last
    talk("rank: unrated", "R0", "PRESENTATION", "BEGINNER", ids["tim"], key="r0")
    talk("rank: 4.0 x1", "R1", "PRESENTATION", "BEGINNER", ids["tim"], key="r1")
    talk("rank: 4.0 x2", "R2", "PRESENTATION", "BEGINNER", ids["tim"], key="r2")
    talk("rank: 4.25->4.3", "R3", "PRESENTATION", "BEGINNER", ids["b500"], key="r3")
    for t, scores in (("r1", [4]), ("r2", [3, 5]), ("r3", [5, 4, 4, 4])):
        for reviewer, score in enumerate(scores, 1):
            go(f"rank: rate {t}", "PUT", "/talks/{" + t + "}/ratings/" + str(reviewer), json.dumps({"score": score}))
    go("rank: tim", "GET", "/talks?sort=rating&speakerId={tim}")
    go("rank: b500", "GET", "/talks?sort=rating&speakerId={b500}")

    # --- misc
    go("health", "GET", "/health")
    go("actuator health", "GET", "/actuator/health")
    go("unknown path", "GET", "/nope")
    return out, ids, tag


def drop(value):
    if isinstance(value, dict):
        return {k: drop(v) for k, v in value.items() if k not in IGNORE}
    if isinstance(value, list):
        return [drop(v) for v in value]
    return value


def norm(text, ids, tag):
    if IGNORE:
        try:
            text = json.dumps(drop(json.loads(text)), separators=(",", ":"), ensure_ascii=False)
        except ValueError:
            pass
    text = text.replace(tag, "TAG")
    text = re.sub(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+00:00', "<ts>", text)
    text = re.sub(r'\d{4}-\d{2}-\d{2}', "<date>", text)
    text = re.sub(r'\d{1,2}/\d{1,2}/\d{2}, \d{1,2}:\d{2} [AP]M', "<usShort>", text)
    rev = {str(v): k for k, v in ids.items()}
    text = re.sub(r'("(?:id|speakerId|talkId)":)(\d+)', lambda m: m.group(1) + "<" + rev.get(m.group(2), "id") + ">", text)
    text = re.sub(r'(/(?:speakers|talks)/)(\d+)', lambda m: m.group(1) + "<" + rev.get(m.group(2), "id") + ">", text)
    return text


old, oids, otag = script(OLD)
new, nids, ntag = script(NEW)
same = diff = 0
framing = set()
for (lbl, s1, h1, b1), (_, s2, h2, b2) in zip(old, new):
    b1, b2 = norm(b1, oids, otag), norm(b2, nids, ntag)
    probs = []
    if s1 != s2:
        probs.append(f"status {s1} -> {s2}")
    if h1["framing"] != h2["framing"]:
        framing.add(f"{h1['framing']} -> {h2['framing']}")
    for k in h1:
        if k != "framing" and h1[k] != h2[k]:
            probs.append(f"{k} {h1[k]} -> {h2[k]}")
    if b1 != b2:
        probs.append(f"body\n      old: {b1[:220]}\n      new: {b2[:220]}")
    if probs:
        diff += 1
        print(f"DIFF {lbl}: " + "; ".join(probs))
    else:
        same += 1
print(f"\nframing differences seen: {framing or None}")
print(f"{same} identical, {diff} different, of {len(old)} requests")
sys.exit(1 if diff else 0)
