# Stakeholder Report – Current State (after Mission M-20251121-824)

This report uses plain language to describe what the system can do today, where its limits are, and where the evidence lives. It is based on the identity audit delivered in `missions/logs/M-20251121-824/`.

---

## 1) What the system does end-to-end (today)

- Starts from a written brief (“spec”) and validates it.
- Generates a structured work plan automatically from the spec (no hand-written plans).
- Turns each plan item into a governed mission with locks, permissions, and an expected sandbox.
- Runs the mission through a single entry point (gateway) that performs: load spec → validate plan → create mission → apply any planned edits → generate code/tests/docs → run checks → simulate merge → run analytics → save a run manifest.
- Records every run under `missions/logs/<mission-id>/` with manifests, logs, and generated artifacts.
- Uses the scheduler to trigger runs; no manual shell steps are required or allowed for normal operation.
- Optional AI assists are available but only as one-shot suggestions through a harness; they never touch files or shells directly and are feature-flagged.

## 2) How work is structured

- **Specs (inputs):** Structured briefs stored as data files (see `resources/specs/` and examples under `missions/logs/M-20251121-823/M-20251121-823-BASE/specs/`). Each spec lists the sections of the governing rulebook it must satisfy.
- **Plans (auto-built):** The planner turns a spec into a directed plan with tasks, dependencies, coverage, and lock information. A sample snapshot lives at `missions/logs/M-20251121-823/M-20251121-823-BASE/plan-snapshot.edn`.
- **Missions (governed tasks):** Missions are created from plan items and carry status, owner, required tracks (code, tests, docs), permissions, and expected tests to run. Example inside `missions/logs/M-20251121-823/M-20251121-823-BASE/context-bundle.edn`.
- **Templates (“CodeTypes”):** A catalog of templates tells the system how to generate code, tests, and docs for common patterns. The live catalog is `resources/dictionary/code_types.edn`; it also holds generator metadata for deterministic output.
- **Artifacts and evidence:** Every run creates a manifest (`run-manifest.edn`), a run log, snapshots, and test outputs. Analytics summaries are copied into the mission log as well.

## 3) The single way to run work

- **Gateway command:** Missions run through `dev/agent_gateway.clj` using the `run-mission` command. The exact CLI template is recorded in `missions/logs/M-20251121-823/M-20251121-823-BASE/context-bundle.edn` under `:gateway/cli`.
- **Scheduler:** `dev/scheduler.clj` calls the gateway and handles queue, priority, retries, and backoff. A scheduler run writes `scheduler-run.edn` next to the mission log.
- **Bundles (context packs):** Before any run, the system builds a “bundle” file that packages the spec, plan snapshot, mission record, locks, sandbox path, expected artifacts, and auth role. Example: `missions/logs/M-20251121-823/M-20251121-823-BASE/context-bundle.edn`.
- **Sandboxing:** Each mission uses its own sandbox directory (see the `:sandbox` block inside the bundle) and a mission branch name. Clean-up and truncation limits are enforced automatically.

## 4) What is automated and proven

- **Spec intake and validation:** Specs are checked for completeness and section coverage before planning.
- **Plan generation and validation:** Planner outputs are validated (coverage, dependency shape, lock conflicts) and snapshotted for traceability.
- **Mission instantiation:** Missions are created only from validated plan nodes and carry required permissions and locks.
- **Edit flow:** Planned edits are applied through a controlled “edit-graph” step; optional AI proposals are routed through the harness and validated before use.
- **Code/materialization:** Code/tests/docs are generated from the template catalog into the mission sandbox, not directly into the main repo.
- **Quality gates:** Lint and automated tests run in every mission. Latest run (M-20251121-824) shows all 47 test suites and lint passing (see `missions/logs/M-20251121-824/lint.txt` and `missions/logs/M-20251121-824/test.txt`).
- **Merge simulation and analytics:** A dry merge and analytics report are produced for each run; outputs are stored in the mission log.
- **Logging:** Logs are truncated to safe sizes (~4 KB) to avoid oversized records; every step writes to the mission manifest.

## 5) Current safeguards and boundaries

- Gateway + scheduler are the only allowed executors; manual shell helpers are flagged for removal.
- All changes flow through missions and documented actions; there is no direct “edit the system” path.
- File writes happen only in mission sandboxes; the main repo is updated only by controlled steps.
- AI usage is opt-in and must return structured, self-reported answers (confidence, assumptions, uncertainties). Calls are logged; failure falls back to deterministic behavior.
- Permissions and locks are enforced per mission (example required permissions recorded in the bundle above).

## 6) Evidence you can open today

- **Most recent audit:** `missions/logs/M-20251121-824/identity-audit-report.md` (what to keep/remove) and `identity-gaps.edn` (machine-readable gap list).
- **Baseline deterministic run:** `missions/logs/M-20251121-823/M-20251121-823-BASE/` contains the bundle, plan snapshot, spec, manifest, and logs for a full pipeline run with AI disabled.
- **AI-ready harness:** `dev/llm_harness.clj` and `dev/codex_oneshot.clj` document the one-shot AI path; requests/responses are stored as data records, not files.
- **Template catalog:** `resources/dictionary/code_types.edn` holds the active templates and generators.
- **Planner rules:** `missions/logs/M-20251121-701/planner-heuristics.edn` documents the deterministic planning rules currently in force.

## 7) Known gaps after the audit (work to do next)

- Remove old run helpers that bypass the gateway (`dev/run_mission.clj`, `dev/run_protocol.clj`, `tmp/run-mission-*`, `tmp/debug-run.clj`).
- Consolidate on a single context bundle format (context-bundle/v1 under `missions/logs/<id>/context-bundle.edn`); retire the extra `agent-context-bundle.edn` files in `missions/logs/M-20251121-823/M-20251121-823-BASE/` and `tmp/agent-context-bundle.edn`.
- Prune draft or placeholder templates from the live catalog (`resources/dictionary/codetype_inference_sample.edn` and draft entries inside `resources/dictionary/code_types.edn`).
- Clean up stray historical runs and sandboxes without manifests (`missions/logs/m-run-*`, `missions/logs/m-log-*`, `resources/specs/m-run-*`, `tmp/mission-standard-stage*`).
- Update `plan.md` and `plan2.md` so they match the “gateway-only” rule and the single bundle format.

## 8) Boundaries of the current system

- Inputs must be structured specs; free-form requests are out of scope until turned into specs.
- The system assumes the template catalog is complete for the work requested. New template types require a proposal mission.
- AI is optional; when off, the system behaves fully deterministically. When on, it still cannot run shell commands or write files directly.
- Deployment beyond analytics/merge simulation is not handled here; this system focuses on spec → plan → mission → validated artifacts.

## 9) Operating guidance for non-technical stakeholders

- To run or re-run a mission, supply a spec and let the scheduler trigger the gateway using the stored bundle command in the mission log. There is no need to craft new commands manually.
- To review evidence, open the mission folder under `missions/logs/<mission-id>/` and read `run-manifest.edn` for the step-by-step record plus `run.log` for the timeline.
- To adjust templates or introduce a new work pattern, open a mission to extend the template catalog rather than editing files directly.
- To enable/disable AI suggestions, adjust the feature flags recorded in `resources/dictionary/llm_integration_plan.edn`; runs will still log and self-report any AI response.

## 10) Short takeaways

- The system is now a single, auditable pipeline from spec to validated artifacts, driven by scheduler → gateway with controlled sandboxes and full logs.
- It already performs plan generation, mission creation, code/test/doc generation, quality gates, merge simulation, and analytics without manual shell work.
- AI help exists but is optional, tightly sandboxed, and fully logged.
- Cleanup tasks remain (remove legacy scripts, prune draft templates, tidy old logs, align docs), but the core path is stable and test-verified.
