# Git Governance

This document defines how missions interact with Git. Agents must follow it whenever they touch the repository. Any exception requires explicit approval through a mission.

---

## 1. Objectives

- Ensure every change is tied to a mission and branch dedicated to that mission.
- Prevent accidental merges/pushes that violate the mission lifecycle.
- Provide consistent commit history and traceability information.
- Prepare for automated enforcement (hooks, CI, future dictionary mirroring).

---

## 2. Branch Strategy

1. **Main branch**
   - `main` is always deployable.
   - No direct commits. All changes land via fast-forward or merge commits originating from mission branches after review.
2. **Mission branches**
   - Named `mission/<MISSION_ID>` (e.g., `mission/M-20251117-001`).
   - One mission per branch; never reuse branches across missions.
   - Branches start from up-to-date `main` (`git pull --rebase origin main`).
   - Branch lifetime = mission lifetime. Delete branch once mission is archived.
3. **Support branches (future)**
   - Release/hotfix branches require a dedicated mission and must obey the same rules.

---

## 3. Commit Requirements

- Every commit message includes the mission ID (e.g., `M-20251117-001: describe change`).
- Commits should be logically grouped, small, and revertible.
- Before committing:
  - Working tree is clean except for intentional changes.
  - Mission worklog contains an entry covering the change.
  - Required tests (defined by mission) are executed or planned; failures must be documented.

---

## 4. Push & Merge Requirements

1. **Verification**
   - Run `scripts/mission-verify.sh` (to be introduced in `M-20251117-002`). This script checks:
     - Current branch name follows `mission/<id>` and matches open mission file.
     - Worklog exists at `worklogs/<mission-id>/WORKLOG.md` with recent entry.
     - Mission board entry is in the correct status file.
     - Working tree is clean and required tests recorded.
   - Hooks (`.githooks/pre-commit`, `.githooks/pre-push`) will call the verify script automatically.
2. **Rebase policy**
   - Rebase mission branch onto latest `main` before requesting review or merging.
   - Resolve conflicts locally; document any tricky merges in the worklog.
3. **Review / Merge**
   - Mission moves to `40_REVIEW` only after verification passes and all deliverables (code/docs/tests) are updated.
   - System owner (or delegated reviewer) merges the branch and tags commits referencing the mission ID, then moves it to `50_DONE`/`60_ARCHIVED`.

---

## 5. Hook & Automation Expectations

- Core enforcement lives in `scripts/mission-verify.sh`.
  - Run it manually via `./scripts/mission-verify.sh manual` to spot issues before staging work.
  - Stages: `pre-commit` (branch + mission/worklog presence) and `pre-push` (adds clean-tree + test-summary checks).
  - For dry-runs outside a Git repo (e.g., CI scaffolding), set `MISSION_VERIFY_ALLOW_NO_GIT=1` and `MISSION_VERIFY_BRANCH=mission/<id>`.
- Repository provides `.githooks/pre-commit` and `.githooks/pre-push` that invoke the script automatically.
  - Install them with `git config core.hooksPath .githooks`.
  - Hooks abort commits/pushes when verification fails; fix the reported issue, update your worklog, then rerun.
- CI environments must run the same script before accepting merges (invoke with `manual` stage plus any additional flags).
- Authentication: GitHub credentials are already stored in `~/.git-credentials` and the `GITHUB_TOKEN` environment variable on the primary machine. Never add secrets to the repo; contact the system owner if access breaks.

---

## 6. Violations & Recovery

- If a rule is violated (e.g., direct commit to `main`), immediately:
  1. Stop work.
  2. Document the incident in the mission worklog.
  3. Coordinate with the system owner to revert or cherry-pick the offending commits.
- Repeat violations trigger a dedicated mission to audit the process.

---

## 7. Change Management

- Modifications to this document require a mission referencing `M-20251117-001` (for traceability) and must include:
  - Rationale for change.
  - Updates to tooling/SOP as needed.
- Keep `docs/20_MISSIONS_AND_AGENTS.md`, `docs/21_AGENT_SOP.md`, and `missions/README.md` in sync with this governance doc.
