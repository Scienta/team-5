# workflow/

Your team's workflow artifacts go here: the process you actually follow from spec to claim, spec
and plan templates, checklists, review rubrics, prompts, scripts for your own gates.

Agent configuration (agents, skills, hooks, commands, settings) goes in `.claude/`.

At 17:00, adding or changing something in `workflow/` or `.claude/` is one of the two conditions
for being eligible to win (the other is a `REVIEW.md` with text under every heading). This README
on its own doesn't count. Link the files from section 1 of `REVIEW.md` so the showcase can point
at them.

## Our gates and tools (team-5)

| What | Where | When |
|---|---|---|
| Test gate: `./mvnw verify` on Java 25 | `.github/workflows/deploy.yml`, job `tests` | every push to `main`, before deploy |
| Smoke gate: the Docker image against a fresh Postgres, `/health` version, every `examples/*.hurl` | `.github/workflows/deploy.yml`, job `smoke` | every push to `main`, before deploy |
| Regression check: the working tree against `origin/main` (or any ref), ~90 requests side by side, prints every difference | `workflow/scripts/compare.sh [ref]` (uses `compare.py`) | before every push that shouldn't change behaviour (refactors, upgrades) |
| Harness status: score, bleed, every check, deployed version vs `origin/main` | `python3 -I workflow/scripts/feedback.py [--watch 60]`, `--timeline <feature>/<check>` | after every push, and while the game runs |

Run them with the production JDK, 25 since the Spring Boot 4 upgrade: `JAVA_HOME=$(/usr/libexec/java_home -v 25) workflow/scripts/compare.sh`.
