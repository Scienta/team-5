"""What the harness sends a feature, and what we answer, from our own request log (RequestLoggingFilter).

    python3 -I workflow/scripts/harness-traffic.py '/status|/withdraw' [--minutes 10] [--all]

The harness runs a feature's checks every minute even before it's claimed, so after deploying a feature (and
before claiming it) this shows the sequences its checks try: e.g. "DELETE twice", "accept without ratings". Prints a
summary per endpoint and status, then the requests of the latest minute in order (--all: every minute).
Reads the Loki URL and read-only token from .fagdag/logs-url and .fagdag/logs-token.
"""
import collections, json, os, re, subprocess, sys, time, urllib.parse, urllib.request

ROOT = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip() or "."


def read(name):
    with open(os.path.join(ROOT, ".fagdag", name)) as f:
        return "".join(f.read().split())


def main(argv):
    if not argv or argv[0].startswith("--"):
        print(__doc__)
        return 2
    pattern = re.compile(argv[0])
    minutes = int(argv[argv.index("--minutes") + 1]) if "--minutes" in argv else 10
    every = "--all" in argv
    start = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - minutes * 60))
    query = urllib.parse.urlencode({"query": '{resource_type="serverless_container"} |= " http "', "limit": 5000,
                                    "start": start, "direction": "backward"})
    req = urllib.request.Request(read("logs-url").rstrip("/") + "/loki/api/v1/query_range?" + query,
                                 headers={"X-Token": read("logs-token")})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
    lines = sorted(json.loads(l)["message"] for s in data["data"]["result"] for _, l in s["values"])
    hits = []
    for m in lines:
        text = m.split(" : ", 1)[-1]
        request = text.split(" -> ")[0]
        if pattern.search(request) and "Warm Up" not in text and "warm-up" not in text:
            hits.append((m[11:19], text))
    if not hits:
        print(f"No requests matching {argv[0]!r} in the last {minutes} min.")
        return 1
    summary = collections.Counter()
    for _, text in hits:
        status = re.search(r"-> (\d+)", text)
        summary[(re.sub(r"/\d+", "/N", text.split(" -> ")[0].split("?")[0]), status.group(1) if status else "?")] += 1
    print(f"{len(hits)} requests matching {argv[0]!r} in the last {minutes} min:")
    for (endpoint, status), n in sorted(summary.items()):
        print(f"  {n:5}  {endpoint}  -> {status}")
    by_minute = collections.OrderedDict()
    for t, text in hits:
        by_minute.setdefault(t[:5], []).append((t, text))
    shown = by_minute.items() if every else [list(by_minute.items())[-1]]
    for minute, rows in shown:
        print(f"\n--- {minute} ({len(rows)} requests)")
        for t, text in rows:
            print(f"  {t} {text[:220]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
