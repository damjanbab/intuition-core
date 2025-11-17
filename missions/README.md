# Missions Directory

This directory replaces the single `MISSION_BOARD.md` file with a structured workspace for drafting, tracking, and archiving missions.

## Structure

- `board/` – Status lists that act as the mission board. Files are prefixed with numbers to keep ordering stable:
  - `00_INBOX.md` – Drafts pending approval.
  - `10_OPEN.md` – Approved missions ready to claim.
  - `20_IN_PROGRESS.md` – Missions currently claimed.
  - `30_BLOCKED.md` – Missions waiting on dependencies or clarifications.
  - `40_REVIEW.md` – Work awaiting owner acceptance.
  - `50_DONE.md` – Accepted work pending archival.
  - `60_ARCHIVED.md` – Permanent historical log.
- `groups/` – Mission group briefs. Each file describes the mini-project scope, dependencies, shared constraints, mission list, and review plan.
- `<MISSION_ID>.md` – One file per mission copied from `_MISSION_TEMPLATE.md`.
- `_MISSION_TEMPLATE.md` – Canonical template for new mission files.

## Board Entry Format

Each status file lists missions using the same compact block:

```
- [`M-YYYYMMDD-###`](../M-YYYYMMDD-###.md) — Title (Owner: <handle or `unassigned`>)
  - Summary: <1–2 sentences>
  - Links: [Worklog](../../worklogs/M-YYYYMMDD-###/WORKLOG.md)
```

Include dependencies or notes inline when helpful. Keep entries sorted by Mission ID for quick scanning.

## Workflow

1. **Draft** – Copy `_MISSION_TEMPLATE.md` to `missions/M-YYYYMMDD-###.md` and fill in every field.
2. **List** – Add the short entry block to `board/00_INBOX.md`.
3. **Approve** – When the system owner approves, move the entry into `board/10_OPEN.md` without changing the mission file.
4. **Claim & Execute** – Agents move entries between status files (`20_IN_PROGRESS`, `30_BLOCKED`, `40_REVIEW`) as work evolves, updating owners and summaries in place.
5. **Done & Archive** – Once accepted, move the entry to `50_DONE.md`. After archival (mission + worklog zipped or referenced), move the entry to `60_ARCHIVED.md` with any postmortem links.

Mission groups: maintain a brief in `missions/groups/<group>.md`. Each mission file references its group. When all missions in a group reach `50_DONE`, run a group review, document outcomes in the brief, then archive the missions.

Always keep the mission file, board entry, and worklog in sync. Status changes happen via git commits—the system owner arbitrates conflicts when multiple agents touch the board.

## Verification & Hooks

- Every mission branch must pass `scripts/mission-verify.sh`.
  - Hooks live in `.githooks/`; enable them via `git config core.hooksPath .githooks`.
  - `pre-commit` enforces branch + worklog presence; `pre-push` additionally requires a clean tree and recorded tests.
- Run the script manually (`./scripts/mission-verify.sh manual`) whenever you are unsure—fix reported issues before retrying.
- Quick-start (run once per fresh clone):
  ```
  git pull --rebase origin main
  git config core.hooksPath .githooks
  ./scripts/mission-verify.sh manual
  ```
  After creating a mission branch (`git checkout -b mission/M-YYYYMMDD-###`), the hooks will block commits/pushes that violate governance rules.

## Worklogs

Worklogs still live under `worklogs/<mission-id>/WORKLOG.md`. The board entries link directly to them; make sure the directory exists when a mission moves past draft.

## Automation Hooks

The structured layout lets future tooling parse folder contents to build dashboards or synchronize with the dictionary. Avoid renaming files or sections without a governance mission.
