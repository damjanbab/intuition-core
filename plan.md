# PLAN – Genesis → Operational SfS

## 1. Stabilize Mission Execution (current system)
- Finish bootstrap → branch snapshot → lint/tests/codetype → report/approval flow.
- Add smoke/integration tests for the dev helpers (`dev.list-missions`, `dev.run-mission`, etc.).
- Continue handing missions manually, capturing evidence under `missions/logs/<mission-id>/`.

## 2. Define Target Spec Schema & Intake
- Introduce TypeDefinition `:spec/target` with fields for requirements, acceptance criteria, test contracts, constraints, etc.
- Build actions/protocol for `spec.capture → spec.validate → spec.publish` (Mission `M-20251121-401`).
- Create validation profiles so every TargetSpec is machine-checkable.

## 3. Build Work Plan DAG & Coverage
- Design WorkPlan schema (PlanNodes, dependencies, CoverageMatrix, proof obligations).
- Implement validators ensuring each requirement/test is covered and no resource conflicts exist.
- Output plan doc + coverage reports for steward approval.

### 3.a Introduce Spec→Plan Generation (no manual plans)
- Build a governed “planner” action/protocol that consumes a captured Spec and emits a WorkPlan EDN (nodes, edges, coverage rows, mission templates) using deterministic heuristics:
  - Group requirements by resource scope/services; keep high-risk/priority requirements as dedicated nodes.
  - Infer test contracts and work tracks per node; ensure doc/system-map tracks are present for structural changes.
  - Derive DAG edges from requirement dependencies and shared resource ordering; attach locks to minimize conflicts.
  - Select mission templates/CI profiles by category/risk (security, ops, feature) and enforce coverage rows (requirements + acceptance criteria → nodes, code targets, test contracts).
- Auto-run plan validation + spec→plan version snapshots immediately after generation; store generation logs so heuristics are replayable/diffable.
- Remove legacy/manual plan-authoring flows once the generator is validated; missions for plan capture become generation + validation checkpoints rather than hand-written WorkPlans.
- Mission instantiation rejects WorkPlans without generator provenance (`:plan.generation/*` metadata + steward-approved status) so manual files cannot bypass the pipeline.
- Deliver through missions that first harden the generator (design, tests, validation) before refactoring existing workflows, to avoid getting lost during the transition.

## 4. Mission Creation From Plans
- Auto-create missions from approved PlanNodes (with declared resources/tests).
- Build finer-grained lock model (ResourceRef + LockManager) for parallel missions.
- Upgrade sandbox protocol: git branch operations + automatic skeleton generation for required CodeTypes.
- Missions are internal artifacts derived from specs/plans; human operators no longer “hand out” missions manually once this step is complete.

## 5. CodeTypes as Templates
- Extend CodeTypes with generators for namespaces/tests/docs.
- Run codetype validation after generation; update system map accordingly.
- Link CodeTypes to TargetSpecs and coverage entries.

## 6. Agent Gateway & Structured I/O
- Expose API endpoints for agents to read specs/plans/missions and submit structured steps (plan refinement, file edit, tool run, decision record).
- Enforce “no shell” rule: every action produces artifacts + audit entries.

## 7. CI & Merge Automation
- Encode CI requirements in Mission Templates; orchestrator runs them automatically and stores logs.
- Automate rebase/merge post-review; archive mission reports + evidence packages.

## 8. Graph-Native Versioning
- Replace Git as the source of truth with graph-native version tracking (spec/plan/mission history in Datomic). Git remains only as a delivery mechanism for final deploy artifacts.

## 9. Automated Agent Orchestration
- Build the scheduler/orchestrator that:
  - Uses the governed list-ready API (no board scraping) to pick missions.
  - Launches an agent session (e.g., via codex exec or the Agent Gateway) with the mission brief.
  - Tracks mission status, validates artifacts, and escalates failures automatically.
- Document agent workflow from the docs: all interactions go through the Agent Gateway (steps: plan refinement, edit, tool run, decision), artifacts are file-based, and sandbox/Git access stays mediated by the runtime.
- Integrate Codex CLI as needed for automated prompt execution.

## 10. Analytics Layer
- Build a post-run analysis tool that ingests spec/plan/mission events and surfaces metrics (lock waits, CI reruns, validator failures, requirement coverage timelines).
- Output a report (`missions/logs/<dry-run>/analysis/*.md`) after each dry run so we can spin up targeted improvement missions immediately.
- Expose quick queries: agent utilization, time from spec → plan → mission → merge, frequent codetype regeneration, etc.

## 11. Consolidation & Audit
- Assign owners/escalation paths for every protocol (spec approval, plan approval, mission merge) once in `SYSTEM_SPEC.md` §5.4 so scheduler/dev tooling can reference the single matrix instead of recomputing it in future missions.
- Verify security/access controls for the Agent Gateway + scheduler (auth, secrets, sandbox policies). Ensure monitoring hooks (lock health, CI retries, gateway timeouts) alert us.
- Run a multi-agent audit to confirm:
  - SYSTEM_SPEC-generated docs cover the entire system.
  - Change logs can be derived from snapshots (no separate manual changelog needed).
  - Hardware/sandbox capacity, backup snapshots, and migration/backfill strategy (new repo built via the system) are ready.
  - Legacy repository is marked read-only; the new, self-describing repo generated by the system becomes the source of truth going forward.
- Produce a consolidated report (`missions/logs/<consolidation>/audit.md`) before the dry run.

### Mission Set for the Audit Program
1. **M-20251121-601 – Audit Prep & Scope**
   - Gather SYSTEM_SPEC/plan references, confirm audit lenses, and publish the shared checklist covering §§2.1–2.2, §3.x, §5, §11.
2. **M-20251121-602 – Spec & Plan Coverage Audit**
   - Inspect spec intake artifacts, work-plan validators, and version snapshots to ensure §9 coverage guarantees hold.
3. **M-20251121-603 – Mission Governance Audit**
   - Review protocol definitions, mission state-machine rules, lock/test/doc/system-map evidence, and report artifacts for compliance with §§3.3–3.6.
4. **M-20251121-604 – Automation & Tooling Audit**
   - Validate CI profiles, codetype generation/validation, analytics runtime, scheduler hooks, and dev helpers per §§5 & 7.
5. **M-20251121-605 – Security & Sandbox Audit**
   - Confirm permission models, JS/API approvals, sandbox bootstrap, and gateway controls satisfy §§2.1–2.2 and §6.
6. **M-20251121-606 – Ops & Deployment Audit**
   - Exercise deploy blue/green/canary/rollback protocols, log evidence, and DR/backfill strategy for §11 readiness.
7. **M-20251121-607 – Audit Synthesis & Steward Report**
   - Aggregate findings from M-601…606, reconcile gaps, and publish the consolidated audit report under `missions/logs/<consolidation>/audit.md`.

### Pre–dry run remediation (from M-20251121-607)
- Fix lineage harness and register the missing CodeDefinition (work-plan demo/sample validator + versioning runtime) so spec/plan snapshots and versioning tests pass (§§3.3–3.6, §5, §11).
- Trim Datomic log payloads (codetype/report artifacts) to clear mission governance/report regressions before review gates (§§3.3–3.6, §5).
- Repair CI merge sandbox path enforcement and extend CodeType coverage/raw analysis to analytics/scheduler/versioning/deploy/js namespaces (§5).
- Harden permissions/approval gates/Agent Gateway auth: new `:permission/security.approve`, default deploy.manage not auto-granted, start! runs tests, JS/API approvals/watermarks recorded (§§2.1–2.2 via §5, §6, §10).
- Wire scheduler → analytics hook so deploy/DR runs auto-archive §11 evidence in mission logs.

### Post-audit: Introduce Spec→Plan Generation (no manual plans)
- After remediation (M-608), add a governed “planner” action/protocol that consumes a captured Spec and emits a WorkPlan EDN (nodes, edges, coverage rows, mission templates) using deterministic heuristics:
  - Group requirements by resource scope/services; keep high-risk/priority requirements as dedicated nodes.
  - Infer test contracts and work tracks per node; ensure doc/system-map tracks are present for structural changes.
  - Derive DAG edges from requirement dependencies and shared resource ordering; attach locks to minimize conflicts.
  - Select mission templates/CI profiles by category/risk (security, ops, feature) and enforce coverage rows (requirements + acceptance criteria → nodes, code targets, test contracts).
- Auto-run plan validation + spec→plan version snapshots immediately after generation; store generation logs so heuristics are replayable/diffable.
- Remove legacy/manual plan-authoring flows once the generator is validated; missions for plan capture become generation + validation checkpoints rather than hand-written WorkPlans.
- Deliver this through dedicated missions that harden the generator (design, tests, validation) before refactoring existing workflows, to avoid getting lost during the transition.

#### Mission ID: M-20251121-701 – Plan Generator Design & Scaffolding
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. All evidence under `/home/dami/intuition-core/missions/logs/M-20251121-701/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5, §9 and `plan.md` §3.a in notes/logs.

**Scope**
- Design the Spec→Plan generator heuristics and schemas; scaffold the planner action/protocol and generation log format. No runtime wiring to mission instantiation yet.

**Tasks**
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-701/`; keep `planner-design-notes.md` there.
2. Heuristics spec: Document deterministic rules (scope grouping, risk split, test/track inference, DAG edges, locks, template/CI selection, coverage rows) in the notes and a machine-consumable EDN (`planner-heuristics.edn`).
3. Schema scaffolding: Update `resources/dictionary/meta-types.edn` (or new EDN) to include planner output entities (generation log, plan provenance). Add stubs in `resources/dictionary/actions.edn` / `protocols.edn` for a `:action/spec.plan.generate` and `:protocol/spec-plan-generate` (no handlers yet).
4. Generation log format: Define an EDN schema for replay/diff (input spec id/version, heuristic decisions, emitted nodes/edges/coverage, warnings). Capture in the notes and EDN.
5. Tests (design-level): Add a placeholder test namespace (e.g., `test/plan_generator_design_test.clj`) that asserts the heuristics EDN is present and well-formed (no nils, required keys exist). This is a contract test, not the final generator.
6. Notes: Summarize open questions + proposed defaults; map each heuristic to SYSTEM_SPEC/plan citations.

**Testing**
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-701/lint.txt`
- `clojure -M:test -n plan-generator-design-test` → `/home/dami/intuition-core/missions/logs/M-20251121-701/test.txt`

**Deliverables**
- `planner-design-notes.md`, `planner-heuristics.edn`, generation log schema/EDN, lint/test logs in the mission log.
- Updated dictionary stubs (actions/protocols/meta-types) capturing the planner scaffolding.

#### Mission ID: M-20251121-702 – Plan Generator Implementation & Validation
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. All evidence under `/home/dami/intuition-core/missions/logs/M-20251121-702/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5, §9 and `plan.md` §3.a.

**Scope**
- Implement the planner action/protocol using the heuristics; auto-run plan validation and spec→plan snapshots; add integration tests.

**Tasks**
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-702/`; keep `planner-implementation-notes.md` there.
2. Implement handler(s) for `:action/spec.plan.generate` and protocol wiring to emit WorkPlan EDN (nodes/edges/coverage/mission-template/locks) plus a generation log.
3. Integrate plan validation + spec→plan version snapshots into the generation flow; ensure artifacts are written/copyable.
4. Add integration tests (e.g., `test/plan_generator_integration_test.clj`) that run spec→plan→validation→snapshot on sample specs and assert coverage/DAG/locks.
5. Update raw code analysis/docs if needed; ensure planner outputs align with meta-types.
6. Record generation logs and snapshots in the mission log; note any heuristic tune-ups.

**Testing**
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-702/lint.txt`
- `clojure -M:test -n plan-generator-integration-test` (plus any targeted suites you add) → `/home/dami/intuition-core/missions/logs/M-20251121-702/test.txt`

**Deliverables**
- Notes, generation logs, snapshots, lint/test logs in the mission log.
- Updated code/dictionary resources implementing the planner.

#### Mission ID: M-20251121-703 – Plan Pipeline Cutover & Cleanup
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. All evidence under `/home/dami/intuition-core/missions/logs/M-20251121-703/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5, §9 and `plan.md` §3.a.

**Scope**
- Remove legacy manual plan-authoring paths; ensure mission instantiation consumes generated plans; verify end-to-end behavior.

**Tasks**
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-703/`; keep `planner-cutover-notes.md` there.
2. Deprecate/remove manual WorkPlan authoring entrypoints; update docs/specs to point to the generator-only path.
3. Ensure mission instantiation pulls from generated, validated plans only; adjust any callers/helpers.
4. Run an end-to-end dry exercise (spec→plan generate→validate→snapshot→mission instantiate) to confirm cutover; capture artifacts and logs.
5. Update SYSTEM_SPEC/plan docs to reflect the new flow; note any residual guardrails.

**Testing**
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-703/lint.txt`
- `clojure -M:test` (targeted suites covering spec→plan→mission flow) → `/home/dami/intuition-core/missions/logs/M-20251121-703/test.txt`

**Deliverables**
- Notes, end-to-end artifacts, lint/test logs in the mission log.
- Updated docs and removed legacy/manual plan paths.

#### Mission Briefs (copy/paste ready, follow Appendix format)

**Mission ID:** M-20251121-601 – Audit Prep & Scope  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Follow each step literally. Verify everything via artifacts under `/home/dami/intuition-core/missions/logs/M-20251121-601/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §3.3–§3.6, §5, §11 and `plan.md` §11 in all notes/logs.

**Tasks**
1. *Mission Log Setup* – `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-601/`; keep running notes in `/home/dami/intuition-core/missions/logs/M-20251121-601/audit-scope-notes.md`.
2. *Inventory References* – enumerate every relevant SYSTEM_SPEC/plan section, spec snapshot, work-plan doc, mission template, and protocol. Capture the matrix inside `/home/dami/intuition-core/missions/logs/M-20251121-601/audit-scope-notes.md`.
3. *Checklist Artifact* – create `/home/dami/intuition-core/missions/logs/M-20251121-601/audit-checklist.edn` describing each audit lens (spec/plan, mission governance, automation, security, ops) with required artifacts/tests.
4. *Scheduling Plan* – document the parallelism/sequencing directly in the notes file so the scheduler knows to launch five auditors across three phases.

**Testing**
- None (planning-only), but run `clojure -M:lint` and save output to `/home/dami/intuition-core/missions/logs/M-20251121-601/lint.txt`.

**Deliverables**
- `/home/dami/intuition-core/missions/logs/M-20251121-601/audit-scope-notes.md`, `/home/dami/intuition-core/missions/logs/M-20251121-601/audit-checklist.edn`, `/home/dami/intuition-core/missions/logs/M-20251121-601/lint.txt`.

---

**Mission ID:** M-20251121-602 – Spec & Plan Coverage Audit  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. All evidence lives under `/home/dami/intuition-core/missions/logs/M-20251121-602/`. Cite `SYSTEM_SPEC` §4, §5.1, §9 and `plan.md` §§2–3.

**Tasks**
1. `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-602/` and capture findings in `/home/dami/intuition-core/missions/logs/M-20251121-602/spec-plan-audit-notes.md`.
2. Re-run spec intake on sample specs and copy the resulting validation/publish/snapshot artifacts (or references) into the same mission-log directory.
3. Re-run work-plan validation on a plan file, saving coverage/DAG/resource outputs into `/home/dami/intuition-core/missions/logs/M-20251121-602/` and documenting any gaps in the notes file.
4. Confirm `versioning/runtime` snapshots show spec→plan links for audited requirements; summarize inside the notes.

**Testing**
- `clojure -M:test -n spec-intake-test -n work_plan_validation_test -n versioning-snapshot-test` → `/home/dami/intuition-core/missions/logs/M-20251121-602/test.txt`.
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-602/lint.txt`.

**Deliverables**
- The notes file plus copied validation/plan artifacts and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-602/`.

---

**Mission ID:** M-20251121-603 – Mission Governance Audit  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Follow Appendix rules. All results under `/home/dami/intuition-core/missions/logs/M-20251121-603/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §5, §11.

**Tasks**
1. `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-603/` and keep `mission-governance-notes.md` there.
2. Inspect `resources/dictionary/protocols.edn`, `src/intuition/sfs/missions/state_machine.clj`, and mission logs (M-01…M-11), copying any critical evidence (e.g., sample `branch.edn` references) into the mission directory.
3. Re-run `mission_state_machine_test` and `integration.mission-flow-test`, saving outputs to `/home/dami/intuition-core/missions/logs/M-20251121-603/test.txt`; confirm branch snapshot artifacts exist and record findings.
4. Summarize whether protocol ownership assignments satisfy §11, noting cross-links to M-601’s checklist in the notes file.

**Testing**
- `clojure -M:test -n mission-state-machine-test -n integration.mission-flow-test` → `/home/dami/intuition-core/missions/logs/M-20251121-603/test.txt`.
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-603/lint.txt`.

**Deliverables**
- Notes, copied evidence, lint/test logs, steward summary all under `/home/dami/intuition-core/missions/logs/M-20251121-603/`.

---

**Mission ID:** M-20251121-604 – Automation & Tooling Audit  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Evidence-driven. Store everything under `/home/dami/intuition-core/missions/logs/M-20251121-604/`. Cite §§5, §7, §10.

**Tasks**
1. `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-604/` and capture notes in `automation-audit-notes.md` within that folder.
2. Exercise CI helpers (`dev/lint.clj`, `dev/run_mission.clj`, `dev/agent_gateway.clj`, `dev/scheduler.clj`, `dev/analytics.clj`). Note command usage and required artifacts.
3. Ensure `docs/code-types/raw_code_analysis.md`, codetype generators, and validation runtime cover every namespace touched recently. Capture `codetype-validation.edn` samples.
4. Verify analytics reports exist and that scheduler TODO references automated analytics hook.

**Testing**
- `clojure -M:test -n dev-tools-smoke-test -n ci-and-merge-automation-test -n analytics-runtime-test` → `/home/dami/intuition-core/missions/logs/M-20251121-604/test.txt`.
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-604/lint.txt`.

**Deliverables**
- Notes, copied CI/codetype/analytics evidence, lint/test logs captured under `/home/dami/intuition-core/missions/logs/M-20251121-604/`.

---

**Mission ID:** M-20251121-605 – Security & Sandbox Audit  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Security mission; log everything under `/home/dami/intuition-core/missions/logs/M-20251121-605/`. Cite §§2.1–2.2, §6, §10.

**Tasks**
1. `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-605/` and maintain `security-audit-notes.md` in that directory.
2. Review `resources/dictionary/permissions.edn`, `src/intuition/sfs/permissions.clj`, sandbox bootstrap, JS/API approval logs, and mission start protocols for gaps.
3. Re-run `js_security_sandbox_test`, `env_isolation_test`, and `agent_gateway_test`; copy evidence referencing approvals/sandbox paths.
4. Document escalation paths for security incidents, referencing M-601 ownership matrix.

**Testing**
- `clojure -M:test -n js-security-sandbox-test -n env-isolation-test -n agent-gateway-test` → `/home/dami/intuition-core/missions/logs/M-20251121-605/test.txt`.
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-605/lint.txt`.

**Deliverables**
- Notes, approval log excerpts, lint/test logs, security risk summary all under `/home/dami/intuition-core/missions/logs/M-20251121-605/`.

---

**Mission ID:** M-20251121-606 – Ops & Deployment Audit  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Ops mission. All artifacts go in `/home/dami/intuition-core/missions/logs/M-20251121-606/`. Cite §§6, §11.

**Tasks**
1. `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-606/` and write `ops-audit-notes.md` there.
2. Run deploy protocols (blue/green, canary, rollback) via `deploy/runtime` helpers or scripted tests; capture evidence EDNs.
3. Check mission logs for deploy evidence (report attachments) and ensure `dev/scheduler.clj` TODO for analytics references operations hooks.
4. Document readiness for DR/migration/backfill (hardware, snapshots) referencing `plan.md` §11.

**Testing**
- `clojure -M:test -n deploy-blue-green-test -n deploy-canary-test -n mission-report-requires-tests` → `/home/dami/intuition-core/missions/logs/M-20251121-606/test.txt`.
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-606/lint.txt`.

**Deliverables**
- Notes, deploy evidence copies, lint/test logs, ops readiness memo all inside `/home/dami/intuition-core/missions/logs/M-20251121-606/`.

---

**Mission ID:** M-20251121-607 – Audit Synthesis & Steward Report  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Final audit mission; archive everything under `/home/dami/intuition-core/missions/logs/M-20251121-607/`. Cite §§3.3–3.6, §5, §11.

**Tasks**
1. `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-607/` and maintain `audit-synthesis-notes.md` there.
2. Ingest artifacts from M-601…606; summarize findings, open issues, and remediation missions.
3. Produce `/home/dami/intuition-core/missions/logs/M-20251121-607/audit-report.md` and `/home/dami/intuition-core/missions/logs/M-20251121-607/audit-report.edn` summarizing compliance status, referencing every lens.
4. Update `plan.md` or scheduler docs with any follow-up missions required before the dry run.

**Testing**
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-607/lint.txt` (sanity check; no new code expected).

**Deliverables**
- Notes, audit report (md+edn), lint log, steward-ready summary citing §11, all under `/home/dami/intuition-core/missions/logs/M-20251121-607/`.

## 12. Proof of Readiness
- Run a full dry run by rebuilding the current Intuition Core system through the new pipelines with the automated scheduler: submit existing specs, derive plans, execute every mission through the Gateway, and merge via the governed flow.
- Validate the artifact/audit trail end-to-end (spec artifacts, plan coverage, mission evidence, merge logs) and generate the analytics report.

### Dry-Run Pilot Mission (simple script end-to-end)
**Mission ID:** M-20251121-DR1 – Script Spec Dry-Run Pilot  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Follow steps literally. All evidence must be under `/home/dami/intuition-core/missions/logs/M-20251121-DR1/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §5, §9, §11 and `plan.md` §12 in all notes/logs. Run this only after M-608 remediation.

**Scenario**
- Minimal target: a tiny script (e.g., "Hello from dry-run") to validate the spec→plan→mission→merge pipeline without complex code.

**Tasks**
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-DR1/`; keep notes in `dr1-notes.md` there.
2. **Spec capture**: Write a minimal target spec EDN (e.g., `tmp/dr1-spec.edn`) describing the script requirement. Run spec intake (`spec.capture` → `spec.validate` → `spec.publish` via protocol or actions) and store artifacts (validation/publish logs, spec snapshot) under the mission log.
3. **Plan capture**: Create a simple WorkPlan EDN (one node: generate script file) and run work-plan capture/validate/publish; store coverage/DAG evidence and plan snapshot.
4. **Mission instantiation**: Instantiate a mission from the plan node; ensure branch/sandbox evidence is captured. Scope limited to the script file path.
5. **Execution**: Run mission-standard (lint/tests/codetype/docs/system-map). Implement the script in the repo (e.g., `src/dev/dry_run_script.clj` or similar) according to the spec. Capture test/log artifacts under the mission log.
6. **Merge simulation**: Use merge automation to simulate merge readiness (no real upstream merge), recording merge logs under the mission log.
7. **Analytics hook**: Trigger `dev.analytics` to produce Markdown/EDN reports; copy outputs into `missions/logs/M-20251121-DR1/analysis/`.
8. **Notes**: Summarize the flow and any deviations in `dr1-notes.md` (cite relevant SYSTEM_SPEC sections).

**Testing**
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-DR1/lint.txt`
- `clojure -M:test` (or targeted suites if you constrain scope) → `/home/dami/intuition-core/missions/logs/M-20251121-DR1/test.txt`

**Deliverables**
- Spec/plan artifacts (validation + publish) and snapshots in the mission log.
- Mission branch/sandbox evidence, test/log outputs, merge simulation logs.
- Analytics report copies (md + edn) under `missions/logs/M-20251121-DR1/analysis/`.
- `dr1-notes.md`, `lint.txt`, `test.txt` in the mission log.

## 13. Cutover
- After the dry run succeeds, stop manual mission handoffs.
- Require all agents to use the spec intake + plan + mission protocols via the scheduler/Gateway.
- Monitor via audit log, analytics, and orchestration dashboards.

## Appendix – Hand-Delivered Mission Format
- Missions must be written with explicit warnings (“zero context, follow steps exactly”) and list the repo root.
- Every instruction references relevant SYSTEM_SPEC sections so agents know the “why.”
- Verification must rely on artifacts in `missions/logs/<mission-id>/`, never terminal scrollback.
- Step-by-step structure:
  1. **Create mission log directory**.
  2. **Enumerate spec references**.
  3. **Ordered tasks** with file paths, actions, and expected artifacts.
  4. **Testing section** specifying commands and required log files.
  5. **Deliverables** list (files to inspect/submit).
- Never assume agents know locks, context, or previous conversations—repeat everything each time.

I’ll treat this as designing a small “OS for multi‑agent software work” from scratch.

---

## 1. High‑level shape of the system

Core components:

* **Spec Registry** – stores Target Specs and their versions.
* **Planner** – turns Specs into Work Plans (DAG of Missions).
* **Mission Orchestrator** – creates sandboxes/branches, enforces locks, runs CI, and advances mission states.
* **Lock Manager** – resource‑level locking across missions.
* **Artifact Store** – immutable files for plans, logs, diffs, test outputs, reports.
* **Audit Log** – append‑only log of every state change and tool invocation.
* **Agent Gateway** – API that all agents (LLM or human tools) must use.

Everything an agent *does* (decisions, edits, commands) is represented as **structured data + files**; no direct shell, no hidden state.

---

## 2. Data model

I’ll use JSON‑ish pseudo‑schemas to be concrete.

### 2.1 Target Spec (product requirements)

```jsonc
TargetSpec {
  spec_id: UUID,
  version: int,
  status: "draft" | "candidate" | "approved" | "deprecated",
  meta: {
    title: string,
    summary: string,
    owner_user_id: string,
    created_at: timestamp,
    last_updated_at: timestamp,
    risk_level: "low" | "medium" | "high",
    tags: string[]
  },
  scope: {
    repositories: RepoRef[],      // which codebases
    services: string[],           // logical services/components
    environments: string[]        // e.g. ["staging", "prod"]
  },
  requirements: Requirement[],    // functional & non-functional
  interfaces: InterfaceContract[],// APIs, events, schemas
  cross_cutting_constraints: Constraint[], // security, perf, compliance invariants
  test_contracts: TestContract[], // spec-level test expectations
  change_impact: {
    related_specs: SpecRef[],
    impacted_domains: ("backend" | "frontend" | "infra" | "data")[],
    estimated_risk: "low" | "medium" | "high"
  },
  validation_profile: ValidationProfile
}
```

#### Requirement

```jsonc
Requirement {
  req_id: string,              // stable, unique within spec
  type: "feature" | "bugfix" | "refactor" | "constraint",
  title: string,
  description: string,         // markdown okay, but not the only source of truth
  rationale: string,
  priority: "must" | "should" | "could",
  dependencies: string[],      // other req_ids
  acceptance_criteria: AcceptanceCriterion[],
  non_functional: NonFunctionalAspect[]
}
```

#### AcceptanceCriterion (machine‑checkable)

```jsonc
AcceptanceCriterion {
  ac_id: string,
  text: string,                 // human explanation
  category: "behavior" | "error_handling" | "performance" | "security" | "ux",
  test_contract_ref: string,    // references a TestContract.id
  must_hold_in_envs: string[]   // ["staging", "prod"] etc
}
```

#### NonFunctionalAspect

```jsonc
NonFunctionalAspect {
  kind: "latency" | "throughput" | "availability" | "cost" | "security" | "observability",
  metric: string,               // e.g. "p95_latency_ms"
  constraint: ComparisonClause  // e.g. { op: "<=", value: 200 }
}
```

#### InterfaceContract

```jsonc
InterfaceContract {
  interface_id: string,
  kind: "rest_api" | "rpc" | "event" | "db_schema",
  location: string,             // file path(s) or schema registry id
  schema: any,                  // OpenAPI/JSONSchema/Avro/etc as raw JSON
  backward_compat: "required" | "best_effort" | "none",
  change_policy: "no_breaking" | "allow_breaking_with_migration"
}
```

#### Constraint

```jsonc
Constraint {
  constraint_id: string,
  kind: "security" | "compliance" | "architecture" | "performance" | "coding_standard",
  description: string,
  machine_rule: MachineRuleRef  // points to executable checks (lint rules, policy-as-code, etc.)
}
```

#### TestContract

```jsonc
TestContract {
  test_contract_id: string,
  name: string,
  level: "unit" | "integration" | "e2e" | "property" | "load",
  description: string,
  // How the system can verify the contract is satisfied:
  locator: {
    repo: RepoRef,
    path_pattern: string,         // glob/regex for test files
    required_annotations: string[] // e.g. markers or tags
  },
  expected_outcome: {
    kind: "pass" | "coverage" | "metric_threshold",
    details: any
  }
}
```

#### ValidationProfile

```jsonc
ValidationProfile {
  profile_id: string,
  required_validators: string[],     // e.g. ["schema", "ambiguity", "test_coverage"]
  // configuration for validators
  settings: {
    min_acceptance_criteria_per_req: int,
    require_test_contract_for_all_must_reqs: boolean,
    forbid_terms: string[]           // e.g. ["fast", "robust"] without being quantified
  }
}
```

This is what ensures “no tribal knowledge”: any TargetSpec can be checked purely by schema rules + validators.

---

### 2.2 Work Plan (spec → missions)

A **WorkPlan** sits between a Spec and Missions.

```jsonc
WorkPlan {
  plan_id: UUID,
  spec_id: UUID,
  spec_version: int,
  status: "draft" | "candidate" | "approved" | "in_execution" | "completed" | "superseded",
  created_by_agent_id: string,
  created_at: timestamp,
  nodes: PlanNode[],          // each becomes one or more Missions
  edges: PlanEdge[],          // DAG dependencies
  coverage_matrix: CoverageMatrix,
  proof_obligations: PlanProofObligation[],
  validation_results: ValidationResult[]
}
```

#### PlanNode

```jsonc
PlanNode {
  node_id: string,
  name: string,
  description: string,
  mission_template: MissionTemplate,
  scope_requirements: string[],       // req_ids from the spec
  resources: ResourceRef[],          // code paths, schemas, infra components
  test_scope: TestScope,
  estimated_effort: string           // free text or enum
}
```

#### MissionTemplate

```jsonc
MissionTemplate {
  mission_type: "feature_impl" | "bugfix_impl" | "migration" | "refactor" | "test_only",
  repo: RepoRef,
  base_branch: string,
  expected_artifacts: ArtifactContract[],  // what files/logs must be produced
  required_validations: string[],          // e.g. ["lint", "unit_tests", "codetype"]
  required_decision_records: string[]      // e.g. ["risk_assessment", "design_choice"]
}
```

#### PlanEdge

```jsonc
PlanEdge {
  from_node_id: string,
  to_node_id: string,
  relation: "depends_on" | "blocks"
}
```

#### CoverageMatrix

```jsonc
CoverageMatrix {
  rows: CoverageRow[]
}

CoverageRow {
  req_id: string,
  ac_id: string,
  implementor_nodes: string[],    // PlanNode.node_id
  code_targets: ResourceRef[],    // where the behavior will live
  test_contract_ids: string[]
}
```

#### PlanProofObligation

```jsonc
PlanProofObligation {
  obligation_id: string,
  description: string,            // e.g. "Every must-have requirement must map to ≥1 test"
  checker_id: string,             // name of a validator
  status: "pending" | "satisfied" | "violated",
  evidence_artifact_id: string | null
}
```

---

### 2.3 Mission

Missions are the unit of work executed by agents.

```jsonc
Mission {
  mission_id: UUID,
  plan_id: UUID,
  plan_node_id: string,
  spec_id: UUID,
  spec_version: int,
  status:
    "planning" | "plan_review" |
    "ready_for_execution" | "in_progress" |
    "awaiting_ci" | "ready_for_review" |
    "failed" | "merged" | "aborted",
  mission_type: string,          // from MissionTemplate
  scope_requirements: string[],  // req_ids
  resources: ResourceRef[],      // write-set for locking
  repo: RepoRef,
  base_commit_sha: string,
  branch_name: string,
  sandbox_id: UUID,
  assigned_agent_id: string,
  locks: ResourceLockRef[],
  steps: MissionStep[],
  artifacts: ArtifactRef[],
  ci_runs: CiRunRef[],
  report_ref: ArtifactRef | null,
  created_at: timestamp,
  completed_at: timestamp | null
}
```

#### MissionStep

```jsonc
MissionStep {
  step_id: UUID,
  kind: "plan_refinement" | "file_edit" | "tool_run" | "decision_record" | "merge_attempt",
  initiated_by_agent_id: string,
  started_at: timestamp,
  finished_at: timestamp,
  input_artifacts: ArtifactRef[],
  output_artifacts: ArtifactRef[],
  parameters: any,                 // tool-specific options
  status: "success" | "failure"
}
```

---

### 2.4 Resources and Locks

#### ResourceRef

```jsonc
ResourceRef {
  resource_type: "file_path" | "directory" | "module" | "db_schema" | "spec_requirement",
  identifier: string,            // e.g. "repo://payments/app/views.py" or "db://users/v3"
}
```

#### ResourceLock

```jsonc
ResourceLock {
  lock_id: UUID,
  resource: ResourceRef,
  mode: "read" | "write",
  holder_mission_id: UUID,
  acquired_at: timestamp,
  expires_at: timestamp | null,
  status: "active" | "released" | "expired"
}
```

Lock invariants:

* At most one **write** lock per resource.
* Read locks allowed concurrently, unless a write lock exists.
* Missions declare desired `resources` up front (from PlanNode), so lock conflicts are known at plan time.

---

### 2.5 Sandbox

```jsonc
Sandbox {
  sandbox_id: UUID,
  mission_id: UUID,
  repo_checkout: {
    repo: RepoRef,
    base_commit_sha: string,
    branch_name: string
  },
  environment: {
    image_ref: string,
    tools_profile: string[],
    env_vars: { [key: string]: string }
  },
  state: "provisioning" | "ready" | "terminated",
  created_at: timestamp,
  terminated_at: timestamp | null
}
```

Agents never touch the real repo or CI environment directly; they only interact with the sandbox via the Agent Gateway.

---

### 2.6 Artifacts and Audit

#### Artifact

```jsonc
Artifact {
  artifact_id: UUID,
  mission_id: UUID | null,      // plan-level artifacts may not be mission-specific
  spec_id: UUID | null,
  kind: "plan_doc" | "plan_graph" | "patch" | "full_file" |
        "test_log" | "lint_log" | "static_analysis" |
        "decision_record" | "mission_report" |
        "ci_summary" | "merge_log",
  path: string,                 // logical path in Artifact Store
  content_digest: string,       // hash for immutability
  created_at: timestamp,
  created_by_agent_id: string,
  metadata: any
}
```

#### AuditLogEntry

```jsonc
AuditLogEntry {
  event_id: UUID,
  timestamp: timestamp,
  actor_id: string,              // agent or human
  action: string,                // e.g. "SPEC_APPROVED", "MISSION_STARTED"
  target: {
    spec_id?: UUID,
    plan_id?: UUID,
    mission_id?: UUID,
    artifact_id?: UUID
  },
  payload: any                   // free-form structured data
}
```

---

## 3. Protocol flows

### 3.1 Idea → Validated Target Spec

**Goal:** Convert an unstructured idea into an *approved* TargetSpec that satisfies all validation rules.

1. **Idea submission**

   * Input: free-form description + context (repos/services).
   * System creates a `TargetSpec` in `status="draft"` with minimal fields.

2. **Spec synthesis (Spec‑Intake Agent)**

   * Agent input:

     * Draft TargetSpec
     * Repo metadata (list of services, modules)
     * ValidationProfile (e.g., requires test contracts)
   * Agent output:

     * Updated TargetSpec (requirements, acceptance criteria, test contracts, interfaces).
   * Output stored as an `Artifact(kind="spec_candidate")`.

3. **Automatic spec validation**

   * Validators run:

     * **Schema validator**: required fields, no malformed id values.
     * **Coverage validator**:

       * Every `Requirement` with priority `must` must have ≥1 `AcceptanceCriterion`.
       * Every acceptance criterion must reference a valid `TestContract`.
     * **Ambiguity validator**:

       * Fails if `description` or `acceptance_criteria.text` contain forbidden vague terms without numeric constraints.
     * **Interface validator**:

       * For each `InterfaceContract.location`, verify referenced schemas exist in repo.
     * **Constraint validator**:

       * Each `Constraint.machine_rule` points to a known rule.

   * Results stored as `ValidationResult` artifacts.

   * If any validator fails → Spec remains `status="draft"`. A new `AuditLogEntry` is created with detailed failures. Human or agent edits and resubmits.

4. **Spec becomes candidate**

   * When all required validators pass:

     * Spec status: `candidate`.
     * System generates a **Spec Validation Report** artifact summarizing covered requirements, test contracts, etc.

5. **Steward review**

   * Human (or higher-privilege agent) reviews the Spec Validation Report.
   * They can:

     * Request changes → status stays `draft` or `candidate`.
     * Approve → status becomes `approved`, `version` increments.
   * Approval logged in `AuditLog`.

Now we have a **machine‑validated, versioned spec** that downstream logic can rely on.

---

### 3.2 Validated Spec → Validated Work Plan (no code yet)

**Goal:** Derive a plan (DAG of Missions) that covers the spec and passes plan‑level proofs.

1. **Plan initialization (Planner Agent)**

   * Triggered by: spec moves to `approved`, or operator selects subset of requirements.
   * Inputs:

     * TargetSpec (approved)
     * Repo structure (directories, modules)
     * Known code ownership / boundaries (optional but baked into RepoRef metadata).
   * Planner Agent outputs:

     * `WorkPlan` with:

       * `PlanNode`s (logical tasks)
       * `PlanEdge`s defining dependency DAG
       * Initial `CoverageMatrix` mapping requirements/criteria to nodes, resources, and test contracts.
     * A Plan Document artifact (`kind="plan_doc"`) describing the design.

2. **Plan proof obligations**

   * System generates `PlanProofObligation`s such as:

     * O1: Every `Requirement` with priority `"must"` has at least one `CoverageRow`.
     * O2: Every `CoverageRow` references at least one `test_contract_id`.
     * O3: For each `NonFunctionalAspect` with a metric constraint, at least one `PlanNode` has `mission_type="test_only" | "feature_impl"` with a matching load or property test contract.
     * O4: Every `PlanNode.resources` list is non-empty and references existing code modules.
     * O5: No two different PlanNodes claim write access to the same `ResourceRef` unless they have explicit ordering (DAG edge).

3. **Automatic plan validation**

   * Validators run on `WorkPlan`:

     * **Coverage validator against spec** (O1, O2, O3).
     * **Resource & lock validator**:

       * Compute planned write sets; flag contention not represented in edges.
     * **Plan DAG validator**:

       * Ensure no cycles; source/sink nodes well-formed.
   * Each obligation marked `satisfied`/`violated` with evidence artifacts (e.g., generated coverage reports).

4. **Plan refinement loop**

   * If any obligation is `violated`, Planner Agent updates the WorkPlan.
   * New version of WorkPlan stored as new artifact; old versions remain immutable (history).
   * This repeats until validation passes.

5. **Plan approval**

   * Once all proof obligations are `satisfied`, `WorkPlan.status = "candidate"`.
   * Steward reviews the Plan Doc + validation summary.
   * On approval: `WorkPlan.status = "approved"` and is now a basis for Missions.

At this stage: **no code has been written**. We only have the spec and a validated, fully covered work plan.

---

### 3.3 Plan Node → Mission execution in sandbox

**Goal:** Execute a Mission safely, with isolated changes, full traceability, and no hidden console.

#### 3.3.1 Mission creation

For each `PlanNode`:

1. **Lock resolution & mission instantiation**

   * Orchestrator requests `write` locks for all `PlanNode.resources`.
   * Lock Manager grants or queues the request.
   * Once locks acquired:

     * A `Mission` is created with:

       * `resources` from PlanNode
       * `scope_requirements` from PlanNode
       * `mission_type` from MissionTemplate
       * `base_commit_sha` chosen from the mainline branch.
       * A unique `branch_name`.
   * A sandbox is provisioned:

     * Fresh checkout of repo at `base_commit_sha` on `branch_name`.
     * Registered tools (lint, tests, static analyzers).
   * Mission status moves to `ready_for_execution`.

2. **Agent assignment**

   * Agent Gateway assigns an agent (human/LLM/tool) to the mission.
   * The agent only sees:

     * Spec subset (requirements in `scope_requirements` and their context).
     * WorkPlan node details.
     * Lock list and `resources`.
     * Sandbox handle (opaque id).

#### 3.3.2 Agent interactions (everything as structured I/O)

Agents interact with the system via an API; some key operations:

* `GET /mission/{id}` – read mission, plan node, relevant spec.
* `LIST /mission/{id}/artifacts` – see all past outputs.
* `POST /mission/{id}/step` – perform a step:

Step kinds:

1. **Plan refinement step**

   * Agent might refine the local implementation plan.
   * Output:

     * Updated sub-plan (e.g., file-level breakdown).
     * `MissionStep(kind="plan_refinement")` with an artifact `plan_refinement.yaml`.

2. **File edit step**

   * Agent sends:

     ```jsonc
     {
       "kind": "file_edit",
       "file_path": "...",
       "change_type": "create" | "modify" | "delete",
       "new_content": "...",
       "rationale_decision_id": "dr-123"
     }
     ```
   * Orchestrator:

     * Verifies `file_path` is within mission’s locked `resources` or allowed subpaths.
     * Writes the file in the sandbox.
     * Creates a git commit.
     * Produces a `Artifact(kind="patch")` with the diff and stores it.
     * Records `MissionStep` with links to artifacts.

3. **Tool run step**

   * Agent calls:

     ```jsonc
     {
       "kind": "tool_run",
       "tool_id": "unit_tests" | "lint" | "codetype" | "static_analysis",
       "args": { ... }
     }
     ```
   * Orchestrator runs the tool **inside the sandbox**.
   * Captures:

     * Full stdout/stderr to `test_log` or `lint_log` artifact.
     * Return code and summary.
   * No console scrolling; logs are files.

4. **Decision record step**

   * Example payload:

     ```jsonc
     {
       "kind": "decision_record",
       "decision": {
         "decision_id": "dr-123",
         "title": "Choose strategy for error handling",
         "context": "...",
         "alternatives": ["A", "B"],
         "chosen": "B",
         "rationale": "...",
         "impacted_requirements": ["REQ-1", "REQ-4"]
       }
     }
     ```
   * Stored as `Artifact(kind="decision_record")`.

All of these steps are atomic and auditable.

#### 3.3.3 Continuous validation within mission

The Mission Template defines **required validations** (e.g., `["lint", "unit_tests", "codetype"]`).

The Orchestrator enforces:

* After any `file_edit` step, the mission enters `awaiting_ci`.
* A CI profile runs inside the sandbox:

  * Lint
  * Static analysis
  * `codetype` check (e.g., files placed in correct directories, no disallowed languages).
  * Targeted tests (based on changed files + TestContract.locator patterns).
* Results are:

  * Stored as artifacts (`test_log`, `lint_log`, `static_analysis`, `ci_summary`).
  * Linked to a `CiRun` record.
* If any required check fails:

  * Mission remains `in_progress`, with `ci_status="failed"`.
  * Agent receives structured CI results via the Gateway and must address them with further steps.

The agent can’t mark the mission as ready until a CI run with all required checks has succeeded.

#### 3.3.4 Mission completion criteria

A mission may transition to `ready_for_review` only if:

* All tasks defined in MissionTemplate are marked done.
* The **local coverage** for its requirements matches Plan coverage:

  * For each `CoverageRow` referencing this `plan_node_id`, there exists:

    * At least one code artifact (patch) touching planned `ResourceRef`.
    * At least one passing test in a CI run that matches the `TestContract.locator`.
* All `required_validations` succeeded in the latest CI run.
* No file outside planned/locked resources was modified.

The agent then calls:

```jsonc
POST /mission/{id}/complete_request {
  "summary": "...",
  "evidence_artifact_ids": [...]
}
```

The Orchestrator verifies the above invariants; if any fail, the call is rejected with structured reasons.

---

### 3.4 Review, merge, archive

**Goal:** Merge only validated missions into mainline, with a permanent trail of evidence.

#### 3.4.1 Steward review

1. **Mission Report generation**

   * Orchestrator synthesizes a report artifact (`kind="mission_report"`), containing:

     * Spec & version.
     * PlanNode info.
     * Requirements and acceptance criteria covered.
     * Summary of file diffs (paths, LOC, high-level classification).
     * CI run summaries (which tests ran & passed, key metrics).
     * List of Decision Records and their impacted requirements.
     * Coverage confirmation (for its subset of the CoverageMatrix).

2. **Human/Review agent review**

   * Reviewer sees:

     * Mission Report (structured).
     * Links to all artifacts (patches, logs, decision records).
     * A read-only view of sandbox code.
   * They may:

     * Run *additional* tools via the Agent Gateway (e.g., extra tests, security scans); results again become artifacts.
     * Approve or reject with comments.

3. **Outcome**

   * If rejected:

     * Mission moves back to `in_progress`.
     * Reviewer comments stored as an artifact.
   * If approved:

     * Status → `ready_for_merge`.

All review interactions are recorded as `AuditLogEntry` events.

#### 3.4.2 Merge protocol

1. **Rebase/update check**

   * Orchestrator checks if mainline has moved since `base_commit_sha`.
   * If so:

     * Rebase mission branch onto latest main.
     * Re-run required CI profile in sandbox.
     * If conflicts:

       * Orchestrator spawns a small “conflict resolution” mission (or requires manual fix).
   * Only after a successful, up‑to‑date CI run does it proceed.

2. **Final checks**

   * Confirm:

     * Locks for mission’s resources are still held (no gap).
     * No changes violate Spec constraints (e.g. security policy checks).
     * Global test suite (or a strong subset) passes if required for high‑risk specs.

3. **Merge**

   * Merge commit created in mainline.
   * Merge log artifact produced (`kind="merge_log"`) including commit ids and any merge specific decisions.
   * Mission status → `merged`.
   * Locks released.

4. **Global traceability update**

   * The system updates an internal trace graph:

     * `Requirement` → `CoverageRow` → `PlanNode` → `Mission` → `MergeCommit` → `TestRun`.
   * This makes it possible to answer “what code & tests implement this requirement?” at any time.

#### 3.4.3 Evidence archival

For each completed mission, the system bundles:

* The Mission Report.
* All artifacts:

  * Plan ref and fragmentation.
  * Patches/diffs and final file snapshots.
  * CI logs and summaries.
  * Decision records.
  * Merge logs.

into an **Evidence Package**, e.g.:

```jsonc
EvidencePackage {
  package_id: UUID,
  mission_id: UUID,
  spec_id: UUID,
  spec_version: int,
  artifacts: ArtifactRef[],
  checksum: string,         // for integrity
  created_at: timestamp
}
```

Stored in durable storage, referenced in the mission and in the audit log.

---

## 4. How agents interact (I/O and traceability)

Agents never bypass the system. They interact through the **Agent Gateway**.

### 4.1 Canonical agent operations

* **Read context**

  * `get_spec(spec_id, scope_requirements)`
  * `get_work_plan(plan_id, node_id)`
  * `get_mission(mission_id)`
  * `list_artifacts(mission_id, filters)`

* **Produce new state**

  * `submit_spec_update(spec_id, updated_spec_fragment)` → new Spec version candidate + validation.
  * `submit_work_plan(plan_id, updated_plan)` → triggers plan validators.
  * `start_mission(mission_id)` → transitions to `in_progress` after locks & sandbox ready.
  * `submit_mission_step(mission_id, step_payload)` → one of the step types.
  * `request_ci_run(mission_id, profile)` → though usually automated.
  * `request_completion(mission_id, summary)`.

Every operation returns structured success/failure with reasons, and every one creates:

* A **MissionStep** (where applicable).
* One or more **Artifacts**.
* An **AuditLogEntry** tying actor, time, and effect.

### 4.2 Traceability guarantees

For any decision or code line, you can trace:

* **From requirement to code:**

  * Requirement → CoverageRow → PlanNode → Mission → Artifact(patch) → Commit/Files.

* **From code line to requirement:**

  * File path & commit → Mission → CoverageRows that involve that ResourceRef → Requirement ids.

* **From test result to spec requirement:**

  * Test log artifact → TestContract.locator path → TestContract id → AcceptanceCriterion(s) referencing it → Requirement.

* **From decision to changes:**

  * DecisionRecord artifact (DR) → MissionSteps that cite `decision_id` as rationale → patches & tests.

This satisfies “every decision and result is traceable” in both directions (spec ↔ plan ↔ code ↔ tests ↔ logs).

---

## 5. Mapping back to the original constraints

1. **Structured Target Specs**

   * `TargetSpec` + `Requirement` + `AcceptanceCriterion` + `TestContract` + `Constraint` provide a strongly typed, machine‑validated representation. ValidationProfile ensures no reliance on tribal knowledge.

2. **Mission can reason about specs and prove its plan**

   * Missions are derived from `WorkPlan` with coverage matrix.
   * Plan‑level ProofObligations + validators must be satisfied before any mission can exist.
   * Mission cannot touch out‑of‑scope resources or requirements without updating the plan (and re‑proving).

3. **Isolated sandboxes, automated checks, file‑based outputs**

   * Each mission gets its own `Sandbox` and `branch_name`.
   * All tooling is invoked via MissionSteps; outputs become `Artifact`s.
   * No console scrollback; only immutable log artifacts.

4. **Missions coordinate with explicit locks**

   * `ResourceLock` model ensures write‑exclusive access to code paths, schemas, and requirements.
   * Lock acquisition is done before mission execution; conflicts are resolved by queueing or DAG ordering.

5. **Automatic merge, report, evidence on completion**

   * Mission → `ready_for_merge` only after validations & review.
   * Orchestrator manages rebase/merge and final CI.
   * MissionReport + EvidencePackage + AuditLog entries give a permanent trail.

---

If you’d like, next step could be to zoom into one part (e.g., the plan validators, or the exact shape of the CoverageMatrix and codetype checks) and turn it into concrete JSON Schemas or interface definitions.
