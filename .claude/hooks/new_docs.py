"""UserPromptSubmit hook: tell Claude about documentation teammates added or changed since it last looked.

Runs on every prompt. Compares HEAD with the commit it saw last time (.git/claude-docs-seen). If commits by someone
other than this clone's git user (i.e. pulled in) touched *.md, workflow/ or .claude/ in between, it injects the list
as context so Claude reads and summarises it before answering. Then it moves the marker to HEAD. Silent otherwise.
The first run only sets the marker.
"""
import json
import os
import subprocess
import sys

DOC_PATHS = ["*.md", "workflow/", ".claude/"]


def git(*args):
    return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, text=True).stdout.strip()


ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
if not git("rev-parse", "--git-dir"):
    sys.exit(0)
marker = os.path.join(ROOT, git("rev-parse", "--git-path", "claude-docs-seen"))
head = git("rev-parse", "HEAD")
seen = open(marker).read().strip() if os.path.exists(marker) else ""

if seen and seen != head:
    base = seen
    if subprocess.run(["git", "-C", ROOT, "merge-base", "--is-ancestor", seen, head]).returncode != 0:
        base = git("merge-base", seen, head) or seen  # history rewritten (rebase): compare from the fork point
    me = git("config", "user.email").lower()
    changes = {}
    commits = []
    for line in git("log", "--no-merges", "--format=%H%x09%ae%x09%an%x09%s", f"{base}..{head}").splitlines():
        sha, email, name, subject = line.split("\t", 3)
        if email.lower() == me:
            continue
        files = git("show", "--format=", "--name-status", sha, "--", *DOC_PATHS).splitlines()
        if files:
            commits.append(f"{sha[:7]} {name}: {subject}")
            for f in files:
                status, _, path = f.partition("\t")
                changes[path.split("\t")[-1]] = {"A": "added", "D": "deleted"}.get(status[:1], "changed")
    if changes:
        listing = "\n".join(f"- {path} ({what})" for path, what in sorted(changes.items()))
        context = (
            "New documentation from teammates arrived with a pull since you last looked "
            f"({base[:7]}..{head[:7]}):\n{listing}\n\nCommits:\n" + "\n".join(f"- {c}" for c in commits) +
            "\n\nBefore answering the user's message, read these files (or their diffs) and give the user a short "
            "summary of what's new: what each document adds, anything that changes how the team works, anything that "
            "conflicts with our rules, workflow/http-conventions.md or REVIEW.md, and anything that needs action. "
            "Then answer their message.")
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "UserPromptSubmit",
                                                 "additionalContext": context}}))

with open(marker, "w") as f:
    f.write(head + "\n")
