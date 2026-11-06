"""Team status from the harness: score, downtime, every check, and whether the deployed version is origin/main.

    python3 -I workflow/scripts/feedback.py            # once
    python3 -I workflow/scripts/feedback.py --watch 60 # every 60 s, printing only when something changes
    python3 -I workflow/scripts/feedback.py --timeline co-speakers/check-2

A check that isn't OK is printed with its notes and failing request; --timeline shows that check's history,
including the transcript of its latest faulty run. Exit code 1 if any claimed check isn't OK or we're down.
Reads .fagdag/harness-url, .fagdag/team and .fagdag/harness-token (never printed). The harness allows bursts of
5 requests, then 1 per second: --watch below 30 s is pointless (one tick is a minute).
"""
import json, os, subprocess, sys, time, urllib.error, urllib.request

ROOT = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip() or "."


def read(name):
    with open(os.path.join(ROOT, ".fagdag", name)) as f:
        return "".join(f.read().split())


HARNESS, TEAM, TOKEN = read("harness-url"), read("team"), read("harness-token")
TEAM_URL = f"https://fagsem-h2026-{TEAM}.scienta.cloud"


def get(url, auth=True, timeout=30):
    req = urllib.request.Request(url)
    if auth:
        req.add_header("Authorization", "Bearer " + TOKEN)
    for _ in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(int(e.headers.get("Retry-After", "2")))
                continue
            return e.code, e.read().decode()
        except Exception as e:  # timeouts, DNS, a container scaling up
            return 0, str(e)
    return 429, "rate limited"


def deployed():
    status, body = get(TEAM_URL + "/health", auth=False)
    try:
        version = json.loads(body).get("version", "?")
    except ValueError:
        version = "?"
    subprocess.run(["git", "-C", ROOT, "fetch", "-q", "origin"], capture_output=True)
    main = subprocess.run(["git", "-C", ROOT, "rev-parse", "origin/main"], capture_output=True, text=True).stdout.strip()
    note = "= origin/main" if version == main else f"!= origin/main {main[:7]} (deploying, failed or rolled back?)"
    return f"health {status} version {version[:7]} {note}"


def report():
    status, body = get(f"{HARNESS}/api/teams/{TEAM}/feedback")
    if status != 200:
        return [f"feedback: HTTP {status} {body[:200]}"], True
    d = json.loads(body)
    score, down = d["score"], d["down"]
    lines = [f"tick {d['tick']}  score {score['total']} (earned {score['earned']}, bled {score['bled']})"
             + (f"  DOWN x{down['streak']}: {down['reason']}" if down.get("current") else "")]
    bad = bool(down.get("current"))
    for f in d["features"]:
        checks = f.get("checks") or []
        if not f.get("claimed"):
            lines.append(f"  {f['slug']:<14} unclaimed")
            continue
        ok = sum(c["status"] == "OK" for c in checks)
        pending = sum(c["status"] is None for c in checks)
        lines.append(f"  {f['slug']:<14} claimed@{f.get('claimedAtTick')}  {ok}/{len(checks)} OK"
                     + (f", {pending} not run yet" if pending else ""))
        for c in checks:
            if c["status"] not in ("OK", None):
                bad = True
                lines.append(f"    {f['slug']}/{c['name']}: {c['status']}  reqs={c.get('reqs')}  notes={c.get('notes')}")
    return lines, bad


def timeline(check):
    status, body = get(f"{HARNESS}/api/teams/{TEAM}/feedback/timeline?check={check}")
    try:
        print(json.dumps(json.loads(body), indent=2))
    except ValueError:
        print(f"HTTP {status}: {body[:500]}")


def main(argv):
    if len(argv) == 2 and argv[0] == "--timeline":
        timeline(argv[1])
        return 0
    if argv and argv[0] == "--watch":
        every = int(argv[1]) if len(argv) > 1 else 60
        last = None
        while True:
            lines, _ = report()
            state = [deployed()] + lines
            if last is None or [l for l in state if not l.startswith("tick")] != [l for l in last if not l.startswith("tick")]:
                print(time.strftime("%H:%M:%S"), "\n".join(state), sep="\n", flush=True)
            last = state
            time.sleep(every)
    lines, bad = report()
    print(deployed())
    print("\n".join(lines))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
