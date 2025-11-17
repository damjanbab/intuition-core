# Missions & Agents

This document codifies how missions are defined, approved, executed, and archived. It is the authoritative contract between the system owner, the orchestrator agent, and every execution agent.

---

## 1. Roles

- **System Owner** – Approves missions, manages concurrency, accepts work.
- **Orchestrator Agent (Codex)** – Maintains docs/system alignment, writes/updates missions, tracks dependencies, coordinates clarifications.
- **Execution Agents** – Take missions from the board, perform code/data/docs/tests work, maintain worklogs, and report results.

All missions must flow through this chain; agents do not self-assign outside the mission board.

---

## 2. Mission Object

Every mission entry must include the following fields:

| Field | Description |
| --- | --- |
| `Mission ID` | `M-YYYYMMDD-###` unique identifier. |
| `Title` | Short imperative phrase ("Implement dictionary storage abstraction"). |
| `Status` | `open`, `in_progress`, `blocked`, `done`, or `archived`. |
| `Owner` | `unassigned` or agent handle. Only the owner may mark `done`. |
| `Summary` | 1–2 sentence intent. |
| `Motivation` | Link to spec gap, roadmap item, bug report, or canonical principle. |
| `Dependencies` | Explicit list of mission IDs or doc updates that must land first. |
| `Deliverables` | Checklist of observable artifacts (code, tests, docs, migrations). |
| `Tests` | Test checklist (commands, scenarios, data fixtures). |
| `Acceptance Criteria` | What the system owner will verify before acceptance. |
| `Rollback / Mitigation` | Plan if deployment causes issues. |
| `Notes` | Any callouts (sandboxes, temporary flags, coordination needs). |

Missions may add optional context (links, diagrams) but may never omit required fields.

---

## 3. Sources of Missions

- **Roadmap gaps** – Items from `03_ROADMAP.md` that are not implemented yet.
- **Spec compliance** – Requirements from `01_NORTH_STAR.md`, `02_SYSTEM_SPEC.md`, or `00_CANON.md` that are missing or drifting.
- **Operational interrupts** – Failing tests, broken deployments, observability gaps.
- **Agent proposals** – Execution agents may suggest missions, but they must be formalized and approved by the system owner via the orchestrator.

---

## 4. Mission Lifecycle

1. **Draft** – Orchestrator prepares the mission using this template and records it in `MISSION_BOARD.md` under the appropriate section.
2. **Approval** – System owner reviews, adjusts, and approves. Only approved missions can move to `open`.
3. **Claim** – An agent declares intent (in `MISSION_BOARD.md`) and switches status to `in_progress`; system owner confirms to avoid collisions.
4. **Execution** – Agent follows `21_AGENT_SOP.md`, keeps a worklog (see §6), and updates mission status if blocked.
5. **Review** – Agent reports results, references tests, and awaits acceptance. Once the system owner confirms completion, the agent (not the owner) moves the board entry to `50_DONE` and notes approval in the worklog.
6. **Archive** – After final housekeeping (merge/tag/deploy) the agent moves the entry to `60_ARCHIVED`, linking to final worklog and PR/commit hashes.

No mission may skip stages or bypass documentation.

---

## 5. Dependency Tracking

- Every mission lists upstream missions and documents they rely on.
- If a dependency slips, downstream missions pause and update status to `blocked`.
- System owner and orchestrator maintain a dependency graph (initially manual) to avoid parallel conflicts.
- When new dependencies are discovered mid-execution, agents must halt work, document the issue, and request mission updates.

---

## 6. Mission Groups, Board, Branching & Worklogs

- **Mission groups (mini-projects)**
  - Definition: A mission group is a themed set of tightly scoped missions that together deliver a mini-project (e.g., "Phase 1: Dictionary Skeleton").
  - Each group has a brief (one-pager) describing goals, shared constraints, dependencies, and review plan. Store briefs under `missions/groups/` (see `missions/README.md`).
  - The orchestrator introduces new groups by adding the brief + seeding related missions; groups move to "Completed" after all constituent missions are archived and the group review is done.
  - Mission files must reference their group in the metadata section (e.g., `- **Group:** Phase-1-Dict-Skeleton`). Use `none` only if a mission explicitly stands alone.
  - Mission sizing rule: if a chain of dependent tasks cannot be split without eliminating safe parallelism (i.e., one agent would have to perform every step sequentially regardless), model it as a single mission rather than multiple tightly coupled missions. Keep the mission in the group but avoid artificial dependency chains that block parallel work.
  - Group review cadence: when every mission in a group is in `50_DONE`, run a structured review → document outcomes → move missions to `60_ARCHIVED` and update the brief with findings.

- **Mission board** now lives in the `missions/` folder, and each mission entry should mention its group (e.g., `[M-20251117-010] — Title (Group: Phase-1-Dict-Skeleton, Owner: ...)`).
  - Read `missions/README.md` for the layout (`board/00_INBOX.md` … `60_ARCHIVED.md`) and the required entry format.
  - Each mission gets its own file `missions/M-YYYYMMDD-###.md` (copied from `_MISSION_TEMPLATE.md`); the board entries link to these files plus their worklogs.
  - Status changes are performed by moving the entry between the numbered files inside `missions/board/`.
- **Branch lifecycle**
  1. Sync `main`: `git pull --rebase origin main`.
  2. Create branch: `git checkout -b mission/<MISSION_ID>`.
  3. Enable hooks once per clone: `git config core.hooksPath .githooks`.
  4. During execution, run `./scripts/mission-verify.sh manual` as needed and ensure commits contain the mission ID.
  5. Before pushing/review: `./scripts/mission-verify.sh pre-push` (automatically called by hooks) and rebase onto up-to-date `main`.
  6. After approval/merge: delete the mission branch and archive the mission entry.
- **Branching & Git rules** live in `docs/22_GIT_GOVERNANCE.md`.
  - Every mission operates on a dedicated branch (`mission/<id>`); no work happens on `main`.
  - Mission status changes must be reflected in Git history (commit messages include the mission ID).
  - Hooks (`.githooks/pre-commit`, `.githooks/pre-push`) call `scripts/mission-verify.sh` and block when requirements are unmet.
  - **Worklogs** are per-mission files stored under `worklogs/<mission-id>/WORKLOG.md`.
    - Agents must use `bin/make-worklog <mission-id> --agent <handle>` to generate the file from the canonical template—manual copies are disallowed.
  - Every agent iteration is timestamped, summarises actions, test commands, and observations.

---

## 7. Tooling Expectations

- Provide scripts or REPL commands for running required tests.
- Maintain shared utilities that parse the mission board, generate reports, or sync status to future system-map surfaces.
- As soon as feasible, mirror mission metadata into the dictionary for automated visualization; until then, keep files consistent.
- GitHub authentication is centrally configured on this machine (`~/.git-credentials` and `GITHUB_TOKEN`). Agents must never commit tokens or replicate credentials inside the repo; report auth issues to the system owner.

---

## 8. Change Process

- Updates to mission governance flow require a dedicated mission referencing this document.
- Execution agents may propose improvements via their mission’s "Follow-up" entry, but cannot modify governance docs directly.
