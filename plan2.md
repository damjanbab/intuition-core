# PLAN2 – Remaining Missions to Complete the System

Context: Missions through M-20251121-802 are complete. The items below are the remaining, executable missions needed to finish the orchestrated, CodeType-aware pipeline and prove readiness. Follow the standard mission format: zero context, explicit root, cite SYSTEM_SPEC, and require artifacts under `missions/logs/<mission-id>/`.

---

#### Mission ID: M-20251121-803 – Scheduler Integration & Cutover  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-803/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §5, §6, §9, §11.

**Scope/Tasks**  
1. Wire scheduler → gateway entrypoint (`run-mission` CLI/HTTP/MCP) with queue/priority/lock metadata; enforce auth/watermarking and retries/backoff.  
2. Dual-run manual vs. orchestrated path on a sample mission (reuse DR1 bundle); flip feature flag to orchestrated-only after green.  
3. Document the exact scheduler command/URL and bundle path pattern; include the equivalent `codex exec` invocation for ad-hoc runs.  
4. Update SYSTEM_SPEC/docs to codify “gateway-only, no manual shell” and cutover steps.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-803/lint.txt`  
- `clojure -M:test` (scheduler/gateway integration + end-to-end dry exercise) → `/home/dami/intuition-core/missions/logs/M-20251121-803/test.txt`  
- Optional Codex smoke: documented `codex exec` driving `run-mission` post-integration.

**Deliverables**  
- Notes, scheduler config/integration artifacts, lint/test logs, cutover flag/config, and the concrete scheduler command template (plus `codex exec` equivalent) under `/home/dami/intuition-core/missions/logs/M-20251121-803/`.

---

#### Mission ID: M-20251121-804 – CodeType Inference & Dedupe Enforcement  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-804/`. Cite `SYSTEM_SPEC` §4.7, §§3.3–3.6, §5, §9.

**Scope/Tasks**  
1. Extend planner heuristics so spec→plan generation assigns CodeTypes automatically from `resources/dictionary/code_types.edn` using scope, paths, change kind/risk, and required artifacts. Specs/missions must not name CodeTypes manually.  
2. Add validation that rejects plans/missions with CodeTypes outside the catalog and detects near-duplicates (same category/artifacts/validators but new ident). Emit a remediation stub mission when a new CodeType is needed.  
3. Enrich CodeType catalog with generator metadata (templates/validators/artifact paths) to keep scaffolding deterministic; ensure “Generate CodeType artifacts” uses these fields.  
4. Update mission-standard/protocol wiring so inferred CodeTypes are generated/validated automatically; document the “no-new-CodeType except via proposal mission” policy.  
5. Integration tests: given a spec with no CodeType hints, the planner emits CodeTypes; mission-standard generates artifacts; dedupe guard fails on a near-duplicate.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-804/lint.txt`  
- `clojure -M:test -n code-type-inference-test -n plan-generator-integration-test` → `/home/dami/intuition-core/missions/logs/M-20251121-804/test.txt`

**Deliverables**  
- Notes, updated planner/generator code, catalog changes, guardrails, lint/test logs, and sample inferred-plan artifacts under `/home/dami/intuition-core/missions/logs/M-20251121-804/`.

---

#### Mission ID: M-20251121-DR1 – Script Spec Dry-Run Pilot  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. All evidence must be under `/home/dami/intuition-core/missions/logs/M-20251121-DR1/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §5, §9, §11 and `plan.md` §12 in all notes/logs. Run after M-803/M-804 are green.

**Scenario**  
- Minimal target: a tiny script (e.g., “Hello from dry-run”) to validate the full spec→plan→mission→merge pipeline via gateway/scheduler, with analytics hook.

**Tasks**  
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-DR1/`; keep `dr1-notes.md` there.  
2. Spec capture: use a minimal spec (e.g., `tmp/dr1-spec.edn`); run spec.capture/validate/publish; copy artifacts/snapshots into the mission log.  
3. Plan generation/validation: run planner (with CodeType inference) → coverage/DAG/locks; store plan EDN + validation outputs in the mission log.  
4. Mission instantiation: create the mission from the plan node; capture branch/sandbox evidence and locks.  
5. Execution: run mission-standard via gateway/scheduler; implement the script in-repo; capture lint/test/codetype outputs in the mission log.  
6. Merge simulation: run merge automation; archive merge logs.  
7. Analytics: run `dev.analytics`; copy Markdown/EDN reports into `missions/logs/M-20251121-DR1/analysis/`.  
8. Notes: summarize flow, deviations, and readiness in `dr1-notes.md`.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-DR1/lint.txt`  
- `clojure -M:test` (or targeted suites you ran) → `/home/dami/intuition-core/missions/logs/M-20251121-DR1/test.txt`

**Deliverables**  
- Spec/plan artifacts + snapshots, mission branch/sandbox evidence, lint/test/codetype logs, merge simulation logs, analytics reports (md+edn), and `dr1-notes.md` under `/home/dami/intuition-core/missions/logs/M-20251121-DR1/`.

---

#### Mission ID: M-20251121-805 – Final Gateway/Codex Compliance Lock-In  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-805/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §5, §6, §9.

**Scope/Tasks**  
1. Document and standardize the codex-driven launch template for `run-mission` (workspace-write, approval `never`), including required context bundle keys and auth.  
2. Add a compliance test harness that feeds a dummy context bundle to `run-mission` via `codex exec` and asserts the expected artifact manifest exists (non-interactive).  
3. Capture MCP tool definition (if used) and ensure agents get catalog/context (CodeTypes, spec, plan) automatically with “no manual shell” enforced.  
4. Bake the template into docs and scheduler ops notes; ensure session termination/cleanup is verified.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-805/lint.txt`  
- `clojure -M:test -n codex-compliance-test` → `/home/dami/intuition-core/missions/logs/M-20251121-805/test.txt`  
- Codex smoke: documented `codex exec` command writes a dummy artifact per manifest; evidence saved.

**Deliverables**  
- Notes, codex templates/tool definitions, lint/test logs, and smoke artifacts under `/home/dami/intuition-core/missions/logs/M-20251121-805/`. Document the exact command used and outcomes.

---

### Graph-Native Code Graph & System Map

#### Mission ID: M-20251121-810 – Code Graph Schema & Projection  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-810/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5.1, §8.1, §9.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-810/`; keep `code-graph-notes.md` there.  
2. Design a normalized “code graph” view over Datomic: nodes for Specs, Plans, Missions, CodeTypes, CodeDefinitions, Tests, Docs; edges for spec→plan→mission, code↔test, code↔doc, code↔system-map. Capture schema + query shapes in notes and an EDN schema (`docs/code-types/code-graph-schema.edn`).  
3. Implement a small runtime (`src/intuition/code/graph.clj`) that queries Datomic and emits the code graph as EDN (`docs/code-types/code-graph.edn`) for a given mission/spec.  
4. Add queries/helpers for “upstream” (spec/plan) and “downstream” (tests/docs/missions) traversal so agents can ask, e.g., “which tests/docs cover this code definition?” Document examples in `code-graph-notes.md`.  
5. Ensure versioning snapshots incorporate references to the code graph (e.g., link snapshot ids to graph nodes) so §8.1 lineage queries can hop into the graph.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-810/lint.txt`  
- `clojure -M:test -n code-graph-query-test` → `/home/dami/intuition-core/missions/logs/M-20251121-810/test.txt`

**Deliverables**  
- `code-graph-notes.md`, `code-graph-schema.edn`, `code-graph.edn`, new runtime + tests, lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-810/`.

---

#### Mission ID: M-20251121-811 – System-Map Integration for Code Graph  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-811/`. Cite `SYSTEM_SPEC` §§4.1, §4.7, §4.10, §7, §9.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-811/`; keep `system-map-code-notes.md` there.  
2. Extend `resources/dictionary/system-map.edn` meta-types to add node kinds for CodeDefinitions and TestDefinitions (e.g., `:system-map/node.code`, `:system-map/node.test`) and edge kinds to represent “implements,” “validated-by,” and “documented-by” relationships, referencing the code graph schema.  
3. Update `intuition.sfs.system-map.runtime` to ingest the code graph EDN from M-810 and materialize code/test nodes and edges into the system-map, ensuring no dangling references.  
4. Provide a CLI helper (e.g., `dev/system_map_code.clj`) or gateway action to rebuild the full system-map including code/test nodes; capture output paths in notes.  
5. Add an integration test (`test/code_system_map_integration_test.clj`) that runs the refresh, asserts no dangling edges, and checks that at least one runtime, test, and doc node are linked.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-811/lint.txt`  
- `clojure -M:test -n system-map-no-dangling-edges-test -n code-system-map-integration-test` → `/home/dami/intuition-core/missions/logs/M-20251121-811/test.txt`

**Deliverables**  
- Notes, updated system-map dictionary/runtime, CLI helper, lint/test logs, and a captured system-map snapshot (including code/test nodes) under `/home/dami/intuition-core/missions/logs/M-20251121-811/`.

---

### Datomic-First Coding Path (Agent Writes Graph, System Writes Code)

#### Mission ID: M-20251121-812 – Datomic Edit Channel Design & Actions  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-812/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5.1, §6, §9.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-812/`; keep `datomic-edit-channel-notes.md` there.  
2. Design a “code proposal” channel: Datomic transactions that add/update CodeDefinitions, TemplateInstances, or Spec fragments as the only allowed way for agents to propose code changes (no direct file edits). Document allowed entity shapes and invariants in notes + EDN (`resources/dictionary/code_edit_channel.edn`).  
3. Add new actions in `resources/dictionary/actions.edn` (e.g., `:action/code.proposal.apply`, `:action/code.proposal.validate`) and handlers in `intuition.sfs.actions.handlers` that validate and apply these proposals, logging them to mission logs and version snapshots.  
4. Ensure proposals are sandboxed and reversible (record before/after snapshots or diff artifacts) and that permissions/roles are enforced for “who can propose what.”  
5. Add tests (`test/code_proposal_channel_test.clj`) that submit sample proposals, reject invalid ones, and verify that no file writes happen at this stage—only Datomic changes and artifacts.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-812/lint.txt`  
- `clojure -M:test -n code-proposal-channel-test` → `/home/dami/intuition-core/missions/logs/M-20251121-812/test.txt`

**Deliverables**  
- Notes, new action definitions/handlers, channel schema EDN, lint/test logs, and example proposal artifacts under `/home/dami/intuition-core/missions/logs/M-20251121-812/`.

---

#### Mission ID: M-20251121-813 – Datomic-Driven Code Generation & Mission-Standard Wiring  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-813/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5.1, §6.2, §7, §9.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-813/`; keep `datomic-codegen-notes.md` there.  
2. Implement a runtime (`src/intuition/code/generate.clj`) that reads CodeDefinitions + CodeTypes from Datomic and materializes code/test/doc artifacts to the sandbox, using the generator metadata already present in `resources/dictionary/code_types.edn`.  
3. Add an action (e.g., `:action/code.materialize.from-graph`) that invokes this runtime for the set of CodeDefinitions touched by a mission, writing files only under the mission sandbox and logging checksums.  
4. Wire `:protocol/mission-standard` (and orchestrator `mission-standard-stage!`) to call this action before lint/tests, so sandbox code is always regenerated from Datomic before validation.  
5. Tests (`test/datomic_codegen_integration_test.clj`) should spin up a small set of CodeDefinitions in Datomic, run mission-standard, assert the expected files appear in the sandbox with matching checksums, and confirm Codetype validation sees them.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-813/lint.txt`  
- `clojure -M:test -n datomic-codegen-integration-test -n mission-standard-stage-test` → `/home/dami/intuition-core/missions/logs/M-20251121-813/test.txt`

**Deliverables**  
- Notes, codegen runtime/action, updated mission-standard wiring, lint/test logs, and sample sandbox code/materialization artifacts under `/home/dami/intuition-core/missions/logs/M-20251121-813/`.

---

#### Mission ID: M-20251121-814 – Agent Edit Flow via Gateway & Codex  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-814/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5, §6, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-814/`; keep `agent-edit-flow-notes.md` there.  
2. Extend `dev.agent-gateway` and/or a dedicated runtime to expose an “edit via graph” command (e.g., `clojure -M:dev -m dev.agent-gateway edit-graph '{...}'`) that lets an agent propose Datomic changes only through the code proposal channel from M-812.  
3. Define the exact context bundle shape for agent edits: which graph slices (spec, plan nodes, code graph neighbors, validation artifacts) are passed in, and how proposals are returned and applied. Capture this in an EDN contract file and notes.  
4. Wire a standard Codex CLI prompt + `codex exec` template that runs `run-mission` or `edit-graph` for a small sample mission, proving the agent can propose graph edits which are then materialized and validated by mission-standard.  
5. Add an end-to-end test harness (could be a scripted test with a fake “agent response” file) that simulates one edit flow without actually calling Codex, verifying all invariants (no direct file edits, Datomic-only proposals, generator writes sandbox code, validations pass).

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-814/lint.txt`  
- `clojure -M:test -n agent-edit-flow-test` → `/home/dami/intuition-core/missions/logs/M-20251121-814/test.txt`

**Deliverables**  
- Notes, gateway/contract updates, sample bundles, lint/test logs, and a captured sample edit-flow run (proposals + resulting code/materialization artifacts) under `/home/dami/intuition-core/missions/logs/M-20251121-814/`.

---

### Agent Context Bundles & Graph Retrieval

#### Mission ID: M-20251121-815 – Agent Context Bundles from Graph & Artifacts  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-815/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5.1, §8.1, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-815/`; keep `agent-context-bundles-notes.md` there.  
2. Define a canonical “agent context bundle” EDN format that packages: spec fragment, plan nodes, mission record, relevant CodeDefinitions/CodeTypes, tests, docs, system-map neighbors, and validation artifacts (spec/plan/mission/merge/analytics) for a given mission id.  
3. Implement a runtime (`src/intuition/gateway/context_bundle.clj`) that queries Datomic + code graph and builds this bundle deterministically, given a mission id and optional focus node.  
4. Integrate this runtime into the orchestrator/gateway so `run-mission` can emit the bundle path in the manifest, and agent launcher scripts (Codex or others) can use it directly.  
5. Add tests (`test/agent_context_bundle_test.clj`) asserting that the bundle is well-formed, contains the expected graph neighborhood and artifact paths, and remains stable across runs for the same mission state.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-815/lint.txt`  
- `clojure -M:test -n agent-context-bundle-test` → `/home/dami/intuition-core/missions/logs/M-20251121-815/test.txt`

**Deliverables**  
- Notes, context bundle runtime/format, gateway/orchestrator integration, and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-815/`, plus at least one captured bundle file for an existing mission.

---

#### Mission ID: M-20251121-816 – One-Shot Reasoning Surface Analysis  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-816/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-816/`; keep `llm-reasoning-surfaces-notes.md` there.  
2. Inventory the entire pipeline (spec-intake, planner, mission-instantiation, mission-standard, merge, analytics, code graph, context bundles) and list every point where non-deterministic reasoning is currently used or might be needed (e.g., interpreting specs, proposing refactors, naming, doc phrasing). Capture this as a table in the notes.  
3. Classify each reasoning surface into: (a) must be deterministic code/data (no LLM), or (b) fits a one-shot LLM call (given bundle → return proposal). For (b), describe the minimal inputs and expected structured outputs, plus the metrics we’ll track (e.g., success rate over N runs) to justify turning it on by default. Surfaces that cannot yet be safely automated should remain unused rather than human-gated.  
4. Produce a machine-consumable EDN taxonomy (`resources/dictionary/llm_surfaces.edn`) enumerating all allowed one-shot LLM tasks with ids, input bundle fields, output schema, invariants (no shell, no files, Datomic-only proposals), and evaluation hooks (how to measure correctness over many runs). For every LLM-capable surface, the output schema must include a lightweight self-report block (e.g., `:meta/self-report` with `:confidence`, `:reason`, `:assumptions`, `:uncertainties`) so we can analyse behaviour statistically without humans in the loop.  
5. Map each surface to SYSTEM_SPEC sections and CodeTypes/protocol steps so later missions can wire the harness without ambiguity and design large-scale automated test suites (e.g., 1000-run evaluation) to “prove” safety where desired, without inserting humans into the loop.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-816/lint.txt`  
- `clojure -M:test -n llm-surfaces-taxonomy-test` → `/home/dami/intuition-core/missions/logs/M-20251121-816/test.txt`

**Deliverables**  
- `llm-reasoning-surfaces-notes.md`, `llm_surfaces.edn`, and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-816/`. Notes must state explicitly which reasoning surfaces will be LLM-driven and confirm that, in principle, all identified (b) surfaces are intended to be handled by one-shot LLM calls once evaluation shows sufficient reliability.

---

#### Mission ID: M-20251121-817 – One-Shot LLM Request/Response Schema & Harness  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-817/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-817/`; keep `llm-harness-design-notes.md` there.  
2. Based on `llm_surfaces.edn`, design EDN schemas for `:llm/request` and `:llm/response` entities (e.g., ids, surface ident, input bundle snapshot, requested outputs, response payload, trace metadata) and add them to `resources/dictionary/meta-types.edn`. `:llm/response` MUST include a `:meta/self-report` map with keys `:confidence` (enum `:low/:medium/:high`), `:reason` (short string), `:assumptions` (vector of short strings), and `:uncertainties` (vector of short strings) so every reasoning call emits structured telemetry.  
3. Implement a runtime (`src/intuition/llm/harness.clj`) that:
   - Takes a surface ident and an input map (usually the agent context bundle or a projection of it).  
   - Constructs a `:llm/request` record and persists it to Datomic.  
   - Optionally (for tests) accepts a “fake response” function instead of calling Codex.  
   - Persists the `:llm/response` record and returns it to callers.  
4. Expose a gateway-friendly API (pure function + optional CLI in `dev/llm_harness.clj`) to run this harness for any surface, without touching files/shell; all I/O is Datomic + EDN.  
5. Add tests (`test/llm_harness_test.clj`) that create requests/responses for multiple surfaces from `llm_surfaces.edn` (not just one), verifying schema conformity, idempotency, and that no file/shell access occurs. The harness should be generic enough that any LLM surface defined in `llm_surfaces.edn` can be exercised without new code.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-817/lint.txt`  
- `clojure -M:test -n llm-harness-test` → `/home/dami/intuition-core/missions/logs/M-20251121-817/test.txt`

**Deliverables**  
- Notes, updated meta-types, `llm/harness.clj` (+ optional dev CLI), lint/test logs, and sample request/response EDN under `/home/dami/intuition-core/missions/logs/M-20251121-817/`. The notes should summarise that the harness supports all LLM surfaces enumerated in `llm_surfaces.edn`.

---

#### Mission ID: M-20251121-818 – Codex One-Shot Integration (Datomic-Only)  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-818/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5, §6, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-818/`; keep `codex-oneshot-integration-notes.md` there.  
2. Implement a thin, external-facing launcher (e.g., `dev/codex_oneshot.clj`) that:
   - Reads an `:llm/request` from Datomic (or a context bundle + surface ident).  
   - Serializes the input into a compact prompt/JSON for Codex.  
   - Calls `codex exec` in a non-interactive, workspace-write, approval `never` mode, passing the input over stdin or as a single prompt.  
   - Parses the structured response and writes an `:llm/response` back into Datomic using the harness from M-817. Codex never sees file paths or shells—only structured data, and it is explicitly instructed to populate the `:meta/self-report` block for every call.  
3. Document the standard Codex prompt template and CLI command in the notes, including model, sandbox, and any environment variables needed.  
4. Add tests/harness scripts that simulate the Codex call by injecting a fake response, so CI does not depend on live Codex. These should verify that only Datomic is touched, not the filesystem, beyond harness logs.  
5. Wire orchestrator/gateway to use this one-shot LLM integration for each reasoning surface marked LLM-capable in `llm_surfaces.edn` (e.g., plan refinement, code proposal, doc phrasing), with feature flags or configuration to enable/disable individual surfaces, so the system can be evaluated safely while still supporting full coverage.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-818/lint.txt`  
- `clojure -M:test -n llm-harness-test -n agent-edit-flow-test` (or additional targeted tests) → `/home/dami/intuition-core/missions/logs/M-20251121-818/test.txt`

**Deliverables**  
- Notes, Codex launcher CLI, wiring into orchestrator/gateway for all LLM-capable surfaces, and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-818/`, plus sample request/response records showing Datomic-only interaction for more than one surface.

---

#### Mission ID: M-20251121-819 – Agent Coding Evaluation & Flow Optimisation  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-819/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-819/`; keep `agent-eval-notes.md` there.  
2. Select or define a small but non-trivial spec (new code + tests + docs, no manual CodeTypes) and capture it under `resources/specs/` with a clear id; record the path and SYSTEM_SPEC sections in the notes.  
3. Use the existing pipeline (spec-intake → planner with CodeType inference → mission instantiation) to create a mission for this spec; then generate an agent context bundle via the M-815 runtime and record its path in the notes.  
4. Run a full mission execution via Codex one-shot integration (M-818) as the coding agent, using the standard `codex exec` template. Capture the exact command, prompt, and any available Codex logs/transcript into the mission log.  
5. Verify that all edits flow through the Datomic edit channel/codegen path (no direct uncontrolled file edits), that mission-standard/materialisation regenerate code/tests/docs, and that merge-sim + analytics complete successfully; list all key artifacts (diffs, validation outputs, analytics reports) in `agent-eval-notes.md`.  
6. Diagnostics: analyse agent behaviour (adherence to instructions, quality of code/tests/docs, failure modes, retries) using both hard outcomes and the `:meta/self-report` fields (confidence/assumptions/uncertainties), and derive concrete heuristics/prompt/policy improvements; document them in the notes and, if needed, as candidate updates to planner/context-bundle logic or `llm_surfaces.edn`.  
7. Optimisation loop: apply at least one small improvement (prompt tweak, context selection filter, or analytics threshold), rerun the mission in a constrained way, and compare metrics (e.g., number of iterations, test failures) using the analytics runtime.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-819/lint.txt`  
- `clojure -M:test -n agent-edit-flow-test -n agent-context-bundle-test` (and any additional harness you add) → `/home/dami/intuition-core/missions/logs/M-20251121-819/test.txt`

**Deliverables**  
- `agent-eval-notes.md`, spec + mission artifacts, exact Codex invocation/prompt, analytics reports (md+edn), lint/test logs, and a clear summary of diagnostics + optimisation changes under `/home/dami/intuition-core/missions/logs/M-20251121-819/`.

---

### LLM Integration Into Planner, Missions, and Scheduler

#### Mission ID: M-20251121-820 – LLM Integration Design & Switches  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-820/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-820/`; keep `llm-integration-design-notes.md` there.  
2. For each LLM surface in `llm_surfaces.edn` (plan-draft, code-proposal, test-doc-suggestions, analytics-digest, code-graph-gaps), decide precisely *where* in the pipeline it will be invoked (planner, mission-standard, edit-graph, analytics) and what bundle slice it will see. Capture this as a “surface → hook” table.  
3. Define feature flags/config toggles for each surface (e.g., `:llm.plan-draft/enabled?`, `:llm.code-proposal/enabled?`) and document the default settings for: dev-local, CI, and production-like runs.  
4. Specify failure/abort behaviour: when an LLM surface returns `:abort` or errors, what deterministic fallback is used (e.g., pure heuristics, skip optional suggestions) and how this is recorded in analytics.  
5. Add a design EDN (`resources/dictionary/llm_integration_plan.edn`) describing the integration plan: surfaces, hooks, flags, abort policies, and metrics (what analytics we’ll watch to evaluate safety).

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-820/lint.txt`  
- `clojure -M:test -n llm-surfaces-taxonomy-test` (to ensure surfaces referenced in the integration plan are valid) → `/home/dami/intuition-core/missions/logs/M-20251121-820/test.txt`

**Deliverables**  
- `llm-integration-design-notes.md`, `llm_integration_plan.edn`, and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-820/`. Notes must call out the exact hook points, flags, and abort policies for each surface.

---

#### Mission ID: M-20251121-821 – Planner LLM Integration (Spec→Plan)  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-821/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-821/`; keep `llm-planner-integration-notes.md` there.  
2. Implement the hook from M-820 for `:llm.surface/plan-draft` inside the spec→plan flow (either directly in `:action/spec.plan.generate` or a closely coupled step): given a captured/validated spec and the existing heuristic plan, call the LLM surface when its flag is enabled and merge the suggested nodes/edges/coverage into the WorkPlan (with validators still enforcing coverage/DAG/resource rules).  
3. Ensure that:
   - When the surface is disabled, behaviour remains identical to today’s deterministic planner.  
   - When enabled and the surface returns `:abort`, the planner falls back to deterministic heuristics only and records the abort in the generation log.  
   - When enabled and the surface returns a proposal, the merged plan still passes existing validation and snapshot steps (no weakening of invariants).  
4. Update `planner-heuristics.edn`, plan generation logs, and any relevant meta-types so that LLM-assisted decisions are traceable (e.g., `:decision/source :planner+llm.plan-draft`).  
5. Extend `plan_generator_integration_test.clj` (and/or add a dedicated `llm_plan_integration_test.clj`) to cover:
   - deterministic-only mode,  
   - LLM-enabled mode with fake responder (no Codex),  
   - LLM abort fallback.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-821/lint.txt`  
- `clojure -M:test -n plan-generator-integration-test -n llm-harness-test` (plus any new LLM planner tests) → `/home/dami/intuition-core/missions/logs/M-20251121-821/test.txt`

**Deliverables**  
- Notes, updated planner action/heuristics/log format, LLM-assisted plan generation code, and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-821/`. The mission log should include at least one LLM-assisted plan generation run recorded via a fake responder.

---

#### Mission ID: M-20251121-822 – Mission LLM Integration (Edit-Graph & Mission-Standard)  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-822/`. Cite `SYSTEM_SPEC` §§3.3–3.6, §4.7, §5.1, §5.3, §6.2, §7, §9.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-822/`; keep `llm-mission-integration-notes.md` there.  
2. Implement the integration plan from M-820 for:
   - `:llm.surface/code-proposal` in the edit-graph/mission pipeline (e.g., generate proposals based on context bundle + code graph instead of relying solely on pre-baked proposals).  
   - `:llm.surface/test-doc-suggestions` to propose additional tests/docs tied to plan nodes/CodeDefinitions.  
3. Wire `orchestrator/edit-graph!` so that, when enabled, it can:
   - call the code-proposal surface via the harness (using `dev.codex-oneshot` or a fake responder),  
   - feed returned proposals into `:action/code.proposal.validate` and `:action/code.proposal.apply`,  
   - then run mission-standard with code materialization and tests as today, while keeping sandbox + locks invariant.  
4. Ensure:
   - all LLM interactions go through the harness; no direct Codex/file/shell access inside orchestrator.  
   - abort/error paths fall back to deterministic behaviour and are logged.  
   - sandbox boundaries remain enforced for all generated code/tests/docs.  
5. Extend `agent_edit_flow_test.clj` (and potentially add a new `llm_agent_edit_flow_test.clj`) to cover:
   - purely deterministic proposal flow,  
   - LLM-driven proposals via a fake responder,  
   - abort/fallback behaviour.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-822/lint.txt`  
- `clojure -M:test -n agent-edit-flow-test -n mission-standard-stage-test -n llm-harness-test` → `/home/dami/intuition-core/missions/logs/M-20251121-822/test.txt`

**Deliverables**  
- Notes, updated orchestrator/edit-graph wiring, LLM proposal integration, and lint/test logs in `/home/dami/intuition-core/missions/logs/M-20251121-822/`, including example manifests/logs for LLM-assisted edit-graph runs (using fake responders).

---

#### Mission ID: M-20251121-823 – Scheduler + LLM Cutover & DR Run  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-823/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5, §6, §8.1, §9, §11.

**Scope/Tasks**  
1. Mission log: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-823/`; keep `scheduler-llm-cutover-notes.md` there.  
2. Update the scheduler (and any related dev tooling) so it can:
   - decide when to invoke LLM surfaces based on mission state and the integration plan from M-820,  
   - call the harness/Codex one-shot path (`dev.codex-oneshot` or equivalent) for those surfaces,  
   - resume missions automatically once responses are persisted, without manual shell interaction.  
3. Define and run a DR-style spec (or reuse DR1 with minimal tweaks) that exercises the full pipeline with LLM enabled: spec-intake → planner (deterministic + plan-draft) → mission-instantiation → LLM-driven code proposals + mission-standard/codegen/tests → merge sim → analytics. Capture all artifacts in the mission log.  
4. Capture scheduler logs/config showing:
   - how it picks missions and surfaces,  
   - how feature flags are configured (which surfaces are enabled),  
   - how failures/aborts are handled.  
5. Use analytics to compare at least one LLM-enabled DR run vs. deterministic-only baseline (time to completion, number of retries, CI failures, abort rates), and record conclusions and any follow-up tuning in the notes.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-823/lint.txt`  
- `clojure -M:test -n scheduler-smoke-test -n run-mission-pipeline-test -n llm-harness-test` (and any additional DR-specific tests) → `/home/dami/intuition-core/missions/logs/M-20251121-823/test.txt`

**Deliverables**  
- Notes, scheduler configuration/logs showing LLM integration, DR run artifacts (spec/plan/mission/merge/analytics + LLM request/response records), and lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-823/`. The notes should explicitly state that, once this mission is green, the system can extend itself from spec to code/tests/docs with LLM assistance and no manual shell interaction.

## System Identity (Reasoning Pipeline) – Snapshot Report (assumes 822/823 wired)

**Observed shape:** Spec intake feeds deterministic planner + optional `plan-draft` surface, validated WorkPlan → mission instantiation → edit-graph with optional `code-proposal`/`test-doc-suggestions` surfaces → mission-standard (codegen/tests/docs/system-map) → merge simulation → analytics. Orchestrator/scheduler is the only executor; Codex is one-shot via the LLM harness; context bundles are the only inputs to LLM; all responses carry `:meta/self-report`.

**Identity as a “general reasoning app”:** Inputs are structured specs; outputs are artifacts + analytics; all reasoning is mediated, stateless, auditable, and feature-flagged. Deterministic validators, locks, and sandboxing remain the safety rails; Datomic + EDN are the single source of truth; file writes happen only through orchestrated actions.

**Inconsistencies / overbuild risk:** Parallel launch surfaces (legacy dev scripts/MCP stubs), ad-hoc context assembly outside the bundle runtime, duplicate logging/snapshots, lingering CodeType scaffolds or catalog drift, and stray “alpha/beta/gama” artifacts not reconciled into the canonical repo. Some docs still reference “manual shell” toggles that no longer match the gateway-only stance.

**Redesign direction:** Collapse to one entrypoint (scheduler→gateway→harness), one context source (bundle runtime), one surface set (`llm_surfaces.edn` + `llm_integration_plan.edn`), and one catalog (`code_types.edn`). Remove legacy prompt/CLI shims, retire unused logs, and codify “reasoning app” obligations so non-code tasks flow the same way (spec→bundle→LLM→materialisation/analytics).

---

#### Mission ID: M-20251121-824 – System Identity Audit & Garbage Collection  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-824/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5, §8.1, §9, §11.

**Scope/Tasks**  
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-824/`; keep `identity-audit-notes.md` there.  
2. Perform a full structure walk: enumerate all entrypoints (dev scripts, MCP stubs, CLI/MCP), context assemblers, CodeType/catalog sources, and logging/snapshot schemes; map each to the canonical reasoning flow (spec→bundle→LLM→materialisation→analytics).  
3. Identify drift/garbage: legacy prompt runners, duplicate context builders, unused logs, stale CodeType templates, and any alpha/beta/gama artifacts not reconciled into this repo.  
4. Produce `identity-audit-report.md` with a canonical flow diagram, the keep/remove list (files/modules), doc mismatches, and a prioritized cut list to align with the reasoning app identity.  
5. Emit a machine-readable `identity-gaps.edn` summarizing gaps/removals (paths, owners, rationale) to drive cleanup missions.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-824/lint.txt`  
- `clojure -M:test -n llm-harness-test -n agent-context-bundle-test` → `/home/dami/intuition-core/missions/logs/M-20251121-824/test.txt`

**Deliverables**  
- `identity-audit-notes.md`, `identity-audit-report.md`, `identity-gaps.edn`, lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-824/`.

---

#### Mission ID: M-20251121-825 – Canonical Entrypoints & Surface Cleanup  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-825/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5, §8.1, §9, §11.

**Scope/Tasks**  
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-825/`; keep `entrypoint-cleanup-notes.md` there.  
2. Apply `identity-gaps.edn`: remove/deprecate redundant launchers/prompts/MCP stubs; route everything through scheduler→gateway→harness (`run-mission` + `codex_oneshot`); update docs/comments accordingly.  
3. Normalize context assembly: enforce the bundle runtime (M-815) as the only context source; delete or redirect ad-hoc assemblers; ensure `llm_integration_plan.edn` is the single switchboard for all surfaces/flags.  
4. Prune duplicate logging/snapshots; keep canonical mission logs + Datomic records + bundle artifacts; replace bulky copies with references where allowed.  
5. Update SYSTEM_SPEC/docs to codify “gateway-only reasoning app,” removal of manual shell toggles, and the canonical entrypoint set.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-825/lint.txt`  
- `clojure -M:test -n run-mission-pipeline-test -n llm-harness-test -n agent-context-bundle-test` → `/home/dami/intuition-core/missions/logs/M-20251121-825/test.txt`

**Deliverables**  
- Notes, updated code/docs, lint/test logs, and a concise “before/after” inventory of entrypoints/context paths under `/home/dami/intuition-core/missions/logs/M-20251121-825/`.

---

#### Mission ID: M-20251121-826 – Generalized Reasoning Templates (Beyond Code)  
**Root Directory:** `/home/dami/intuition-core`  
**WARNING:** Zero context. Evidence under `/home/dami/intuition-core/missions/logs/M-20251121-826/`. Cite `SYSTEM_SPEC` §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11.

**Scope/Tasks**  
1. Log setup: `mkdir -p /home/dami/intuition-core/missions/logs/M-20251121-826/`; keep `generalized-reasoning-notes.md` there.  
2. Extend `llm_surfaces.edn` and meta-types to cover non-code “computer work” templates (e.g., structured analyses, policy summaries, data classification) with self-report and deterministic schemas; add exemplar specs under `resources/specs/`.  
3. Ensure context bundles project the right slices (docs, analytics, graph neighbors) for these tasks; update the bundle runtime if needed.  
4. Add a small E2E sample mission (spec → plan → mission → LLM surface → materialized EDN/markdown report) that produces no code changes but writes results to Datomic/mission log.  
5. Update docs to state the pipeline now supports general reasoning tasks, including expected artifacts/outputs for these templates.

**Testing**  
- `clojure -M:lint` → `/home/dami/intuition-core/missions/logs/M-20251121-826/lint.txt`  
- `clojure -M:test -n llm-harness-test -n agent-context-bundle-test -n plan-generator-integration-test` → `/home/dami/intuition-core/missions/logs/M-20251121-826/test.txt`

**Deliverables**  
- Notes, updated surfaces/meta-types/bundle projections, sample non-code spec + mission artifacts, lint/test logs under `/home/dami/intuition-core/missions/logs/M-20251121-826/`.

---
