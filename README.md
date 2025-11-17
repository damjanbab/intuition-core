# Intuition Core – Bootstrap Repo

This repository currently hosts the documentation and workflow scaffolding for the self-describing app factory. The runtime code will arrive later; for now the focus is on missions, governance, and Git safety rails.

## Quick Start

1. **Clone the repo**
   ```bash
   git clone <repo-url>
   cd intuition-core
   ```
2. **Sync main and create a mission branch**
   ```bash
   git pull --rebase origin main
   git checkout -b mission/M-YYYYMMDD-###
   ```
3. **Enable Git hooks & run the verifier**
   ```bash
   git config core.hooksPath .githooks
   ./scripts/mission-verify.sh manual
   ```
   Or run the helper:
   ```bash
   bin/setup-hooks.sh
   ```
4. **Follow the mission workflow**
  - Claim a mission in `missions/board/10_OPEN.md`.
  - Run `bin/make-worklog <mission-id> --agent <handle>` to scaffold `worklogs/<mission-id>/WORKLOG.md`.
  - Use `bin/move-mission <mission-id> <status> --owner <handle> --summary "<intent>"` to move entries between board files instead of editing Markdown manually.
  - Run `bin/mission-report [--group WORKFLOW_AUTOMATION] [--status review]` to inspect per-group/status counts before planning or hand-off.
   - Use `scripts/mission-verify.sh` (hooks run it on commit/push) to enforce branch, worklog, and clean-tree requirements.
   - Update docs/tests per mission deliverables, then move entries through the board.

## Key Documents

- `docs/00_CANON.md` – Foundational principles.
- `docs/01_NORTH_STAR.md` – Target end-state.
- `docs/02_SYSTEM_SPEC.md` – Concrete requirements.
- `docs/03_ROADMAP.md` – Phase plan.
- `docs/20_MISSIONS_AND_AGENTS.md` – Mission governance & branching lifecycle.
- `docs/21_AGENT_SOP.md` – Detailed agent workflow.
- `docs/22_GIT_GOVERNANCE.md` – Git rules + mission-verify expectations.

## Mission Directory

See `missions/README.md` for the board structure, mission template, and verification rules. All missions live in the `missions/` folder with status tracked via numbered board files.
