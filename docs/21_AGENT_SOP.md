# Agent SOP

This SOP defines how execution agents operate when working on missions for the self-describing app factory. Deviations require explicit written approval from the system owner.

---

## 1. Prerequisites

- Read and understand `00_CANON.md`, `01_NORTH_STAR.md`, `02_SYSTEM_SPEC.md`, `03_ROADMAP.md`, `20_MISSIONS_AND_AGENTS.md`, and `22_GIT_GOVERNANCE.md`.
- Before touching a mission, read the relevant **mission group brief** (stored under `missions/groups/`); it anchors the context, dependencies, and review plan.
- Ensure the local dev environment can run the stack: Datomic, Ring server, HTMX/Hiccup UI, Garden styles, bidi routing (or approved alternative), and the full test suite.
- Verify you have a dedicated terminal session; agents must not share shells or rely on side channels.
- GitHub credentials are preconfigured on this machine via `~/.git-credentials` and `GITHUB_TOKEN`; never write tokens into the repo or logs. If authentication fails, notify the system owner instead of re-adding secrets.
- Configure Git hooks once per clone: `git config core.hooksPath .githooks`. Confirm `scripts/mission-verify.sh manual` runs successfully before starting work.

---

## 2. Mission Intake

1. Sync `main` (`git pull --rebase origin main`) and create a branch `git checkout -b mission/<MISSION_ID>`.
2. Read the mission group brief referenced in the mission file (e.g., `missions/groups/Phase-1-Dict-Skeleton.md`).
3. Open `missions/board/10_OPEN.md` (see `missions/README.md`) and locate missions ready to claim. Confirm dependencies are satisfied and Git requirements in `docs/22_GIT_GOVERNANCE.md` (branch, hooks, clean tree) hold.
4. Announce intent to claim by moving the entry to `missions/board/20_IN_PROGRESS.md`, setting yourself as owner, and notifying the system owner (per agreed communication channel).
5. Generate the worklog with `bin/make-worklog <MISSION_ID> --agent <handle>` (never copy templates manually) and record the initial timestamp + intent in the freshly created file.

No work starts before the mission is officially assigned.

---

## 3. Execution Loop

For each iteration:

1. **Plan** – Break the mission into substeps referencing specs and dependencies.
2. **Implement** – Make the smallest coherent change; keep commits atomic when possible.
3. **Document** – Update the worklog with:
   - Timestamp
   - Description of actions
   - Files touched / commands run
   - Test results (pass/fail)
   - Next steps or blockers
4. **Verify** – Run required tests plus any impacted suites (unit, integration, end-to-end). Capture command invocations and run `./scripts/mission-verify.sh manual` to confirm status before staging significant changes.
5. **Sync** – If blocked, update the relevant file under `missions/board/` (typically `30_BLOCKED.md`) with a concise note and capture the same information in the worklog.

---

## 4. Testing Requirements

- **Baseline**: Run all tests listed in the mission’s `Tests` section.
- **Regression**: Re-run affected suites in addition to baseline when touching shared code.
- **Smoke**: Before handing off, run a minimal smoke test (server start, key flows) to ensure functionality.
- Document every command and result in the worklog; failures must be resolved or explicitly escalated.

---

## 5. Completion & Review

1. Ensure code, docs, migrations, and tooling updates match mission deliverables.
2. Run the full required test checklist and capture results. Rebase onto latest `main`.
3. Execute `./scripts/mission-verify.sh pre-push` (hooks do this automatically, but run it manually if unsure) and ensure the worklog’s test summary is complete.
4. Update worklog with a final summary, including commit hashes and outstanding risks.
5. Move mission to `Review`, ping the system owner with:
   - Summary of changes
   - Link to worklog
   - Tests run and outcomes
   - Follow-up mission suggestions (if any)
6. Include group status in the hand-off (e.g., "Group Phase-1-Dict-Skeleton: mission 3/5 complete").
7. After approval, archive the mission per `20_MISSIONS_AND_AGENTS.md` and remove local branches as instructed.
8. Agents (not the owner) update the board entry after each confirmation: move from `40_REVIEW` → `50_DONE`, then to `60_ARCHIVED` once final steps are complete. When a group’s last mission lands in `50_DONE`, flag the orchestrator for group review.

---

## 6. Communication Rules

- Use precise, auditable channels (e.g., repo issues, structured chat) for questions and status updates.
- Never rely on implicit knowledge; reference line numbers or file paths when describing changes.
- If ambiguity in canonical docs arises, stop work and request clarification via the system owner/orchestrator.

---

## 7. Tooling Expectations

- Prefer `rg`, `clj -M:test`, `clj -M:repl`, and scripted helpers committed to the repo.
- Scripts modifying shared state must be idempotent and documented.
- When instrumentation/logging is added, ensure metrics are exposed and described in the worklog.

---

## 8. Continuous Improvement

- Each mission deliverable includes identifying potential automation or documentation gaps.
- Propose follow-up missions to improve the mission system, testing harness, or developer tooling.
- Never retrofit governance changes inside unrelated missions; request a dedicated mission via the orchestrator.
