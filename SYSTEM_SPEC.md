# Intuition System & System-for-System (SfS) Specification – v0.3

_Last updated: 2025-11-18_

This document defines:

1. **The Intuition Platform (“Dictionary”)** – a dictionary-centered system that can describe and assemble any app the business needs.
2. **The System-for-System (SfS)** – the meta-layer of missions, actions, and protocols that lets agents safely evolve Intuition itself.

Everything that matters is represented as **data** in Datomic (types, templates, apps, missions, actions, protocols, docs, system map). Code is an implementation detail that interprets that data.

---

## 0. Terminology

- **Dictionary** – Datomic entities describing all structural concepts (types, attributes, templates, apps, actions, protocols, missions, docs).
- **Runtime** – Clojure + Datomic + HTTP server that interprets dictionary data to render UI and run behavior.
- **SfS (System-for-System)** – actions + protocols + tooling that govern how the system is changed.
- **Mission** – atomic unit of change with explicit deliverables, tests, and traceability.
- **Action** – atomic deterministic operation (run tests, generate code, update docs, etc.) with a strict contract.
- **Protocol** – ordered composition of actions plus decision points.
- **Agent** – execution actor following protocols (human using REPL, or future automated agent).
- **Steward** – human owner responsible for priorities, approvals, and safety.

**Global invariant G0**:

> Any structural mutation (schema, templates, apps, actions, protocols, deployments) MUST be traceable to exactly one mission and executed via protocolized actions. No direct ad-hoc mutations.

Enforcement: `:action/datastore.tx` wrapper; `protocol/guard-mutation` middleware on all REPL entrypoints that write Datomic.

**M-20251121-608 updates:** CodeType catalog now includes versioning/analytics/scheduler assets (§§4.7, §8.1, §11); JS/API approvals require `:permission/security.approve` (no default deploy.manage) with §2.1–§2.2/§6/§10 watermarks; Agent Gateway steps and mission `start!` enforce test execution and audit stamps; action execution logs are trimmed (~4k chars) so codetype/report payloads stay under Datomic size limits.

---

## 1. Vision & Non-Negotiable Principles

### 1.1 Vision

- **Describe the system; don’t hand-wire it.**
- **System describes itself.**
- **Missions & protocols enforce correctness by construction** rather than via meetings and vibes.
- **Mission creation = execution plan.** Structural definitions (TypeDefinitions, TemplateInstances, ActionDefinitions, CodeTypes, ProtocolDefinitions) are the canonical act of execution. Once the record exists inside the dictionary, runtimes/actions/protocols MUST finish the job deterministically (docs, system map, validation, deployments). Human/agent effort is therefore focused on describing the change; the system enforces/executes it.
- **Mission creation = execution plan.** Describing a change as data (TypeDefinition, ActionDefinition, CodeType, TemplateInstance, ProtocolDefinition) is the primary act of execution. Once the structural record exists, the runtime is expected to carry out validation, doc/system-map updates, and deployments deterministically. Agents focus on defining/curating those structures; the system handles the rest.

### 1.2 Non-Negotiable Principles (with invariants)

1. **Dictionary = single structural source of truth.**

   - P1.1: No production behavior may depend on out-of-band configuration (YAML, JSON, env files) that isn’t represented in the dictionary.
   - P1.2: Any long-lived wiki/spec text MUST be mirrored into `:spec/section`.

   Enforcement: `:action/config.scan` + CI checks; `:action/spec.sync-from-markdown`.

2. **Apps from data.**

   - P2.1: New app UIs and endpoints MUST be composed from TemplateInstances, AppDefinitions, and NavItems. Direct hard-coded routes/views in Clojure are forbidden except for bootstrapping.
   - P2.2: Introducing a new behavior primitive MUST happen via TemplateDefinition or ActionDefinition.

3. **SfS enforces correctness.**

   - P3.1: Every mission MUST declare scope, work-type matrix, and acceptance tests.
   - P3.2: Mission completion protocol MUST fail if required worktracks/tests/docs/system-map steps are missing.

4. **Computable documentation & system map.**

   - P4.1: Every structural entity MUST have a system-map node.
   - P4.2: Docs for those entities MUST be viewable via doc templates.

   Enforcement: `:action/system-map.refresh`, `:action/docgen.*`, `test/system-map-coverage`.

5. **Automation first.**

   - P5.1: Repeated manual sequence (≥2 times) MUST have a mission to automate it.
   - P5.2: Manual-only missions must include a follow-up automation mission reference.
   - P5.3: Mission validation MUST record `clojure -M:lint` (deps `:lint` alias + `resources/dictionary/clj_kondo/config.edn`) before `clojure -M:test` so dictionary data, runtime namespaces, and tests all analyze cleanly. `clojure -M:dev -m dev.lint` is the single-command helper for agents/automation.

6. **Everything is a system.**

   - P6.1: Mission creation/revision/abort/regression MUST be protocolized.

7. **Clojure-first interaction.**

   - P7.1: All structural mutations go through `intuition.sfs/*` entrypoints backed by actions/protocols.
   - P7.2: Direct Datomic transacts for structural entities are forbidden outside those entrypoints.

8. **Performance, safety, multi-user readiness.**

   - P8.1: All HTTP endpoints MUST be associated with AppDefinition + TemplateInstance.
   - P8.2: Authorization checks MUST run before TemplateInstance execution.

9. **Missions as atomic truth units.**

   - P9.1: Each artifact is owned by at most one mission at a time.
   - P9.2: Missions never reopen; follow-up work is a new mission.

10. **No invisible changes.**

- P10.1: Every commit MUST reference a mission ID.
- P10.2: Every deployment MUST reference missions.

---

## 2. Actors, Roles & Trust Boundaries

### 2.1 Roles

Entities: `:user/account`, `:role/definition`, `:permission/definition`.

Invariant R1:

> A user MAY have multiple roles; a role MUST define a set of permissions; actions/protocols MUST check permissions before executing.

Key roles: `:role/steward`, `:role/dictionary-engineer`, `:role/ops`, `:role/reader`, `:role/mission-generator`.

### 2.2 Trust Boundaries

- Steward can approve missions/deployments, revise scopes, approve JS components/external APIs.
- Dictionary engineers can modify schema/templates/apps.
- Ops can run deploy/recovery protocols.

Invariant R2:

> No single non-steward role may both modify production behavior and approve deployment of that behavior.

Enforcement: role checks in deploy/evolution protocols.

### 2.3 Permission Catalog

`resources/dictionary/permissions.edn` seeds the concrete `:permission/definition` and `:role/definition` entities that enforce §§2.1–2.2, §5, and §6. Every action/protocol pulls its guardrails from this data so runtime checks never drift from the dictionary. The following table summarizes the canon:

| Permission ident | Summary | Roles |
| --- | --- | --- |
| `:permission/env.bootstrap` | Creates per-mission sandboxes before any mutation (§6.2–§6.6). | `:role/steward`, `:role/dictionary-engineer`, `:role/ops` |
| `:permission/locks.manage` | Acquires/releases queue + scope locks (§3.2–§3.3). | `:role/steward`, `:role/dictionary-engineer`, `:role/ops` |
| `:permission/tests.run` | Executes governed regression suites (§3.1, §5.1). | `:role/steward`, `:role/dictionary-engineer`, `:role/ops` |
| `:permission/docs.write` | Refreshes docs/docgen outputs (§4.2, §4.9). | `:role/steward`, `:role/dictionary-engineer` |
| `:permission/system-map.write` | Updates system-map nodes/edges (§4.1, §4.5). | `:role/steward`, `:role/dictionary-engineer` |
| `:permission/missions.manage` | Validates/transitions missions plus JS/API governance (§§3.4–3.12). | `:role/steward`, `:role/dictionary-engineer`, `:role/ops` |
| `:permission/missions.report` | Submits steward-ready mission reports (§3.4–§3.6). | `:role/steward`, `:role/dictionary-engineer`, `:role/ops` |
| `:permission/missions.log` | Records worklogs/evidence (§3.4, §9). | `:role/steward`, `:role/dictionary-engineer`, `:role/ops` |
| `:permission/deploy.manage` | Stages/promotes/rolls back deployments (§6). | `:role/steward`, `:role/ops` |

`:role/reader` and `:role/mission-generator` remain read-only/backlog roles (no `:role/permissions` assignments). The steward role is a strict superset of dictionary-engineer and ops, satisfying invariant R2 while keeping deployment guardrails explicit.

---

## 3. Mission Lifecycle & Governance

### 3.1 Mission Schema & State Machine

Mission entity fields include ID, title, summary, category, priority, status, protocol/version, scope, prerequisites, deliverables, work-tracks, tests, spec reference, etc.

States: `:draft`, `:ready`, `:in-progress`, `:revision`, `:awaiting-review`, `:abandoned`, `:done`, `:archived`.

Allowed transitions enforced via `protocol/mission-transition`.

### 3.2 Scope & Locking

- Missions must declare scope (entity paths, namespaces, files, environments, impact).
- Locks acquired via `:action/lock.acquire`; released via `:action/lock.release`.

### 3.3 Work-Type Matrix & Readiness

- All required work tracks must be set before `:ready`.
- Must be `:done` with worklogs before `:awaiting-review`.

Mission readiness now includes a **branch snapshot** checkpoint. `:protocol/mission-sync` (called automatically by `:protocol/mission-standard` and `start-mission`) runs `:action/env.bootstrap` and the new `:action/git.branch.prepare`, which writes `missions/logs/<id>/branch.edn` + `branch.md` referencing §6.2. Transitioning to `:awaiting-review` or submitting a mission report fails unless that artifact exists so the steward has sandbox/branch evidence tied to the mission.

### 3.4 Worklogs & Evidence

- Every mission `:in-progress` must have worklogs; completion requires worklogs per required track plus artifacts.

### 3.5 Approval & Completion

- Single mandatory checkpoint at mission completion.
- `protocol/mission-report` verifies deliverables/tests/docs/system-map.
- Steward approves via `protocol/mission-approve`; mission archives via `protocol/mission-archive`.

### 3.6 Mission Priority & Assignment

- Missions carry priority and queue tags.
- `start-mission` selects highest-priority ready mission, with conflict checks.

### 3.7 Tooling & Execution Environments

- Sandbox per agent (`protocol/env-bootstrap`).
- Standard commands: `list-ready-missions`, `start-mission`, `log-step`, `mission-report`, `sync-actions/protocols`.
- Mission test protocol now runs `clojure -M:lint` (or `clojure -M:dev -m dev.lint`) prior to `clojure -M:test`, and both outputs are captured in mission logs + `/tmp/test.log` to enforce §§1.2 & 5.3 lint invariants.
- Protocols MUST emit command output/evidence as structured files (e.g., `/tmp/test.log`, `missions/logs/<id>/...`). Human/agent communication happens via these artifacts rather than raw terminal scrollback so automated consoles can read/relay results without missing truncated output.

### 3.8 Archiving & Historical Access

- Archive snapshots include metadata, worklogs, artifacts, reports.

### 3.9 Change Requests & Revision

- Steward uses `protocol/mission-revise` to lock mission, edit scope/deliverables, then return to ready or archive.

### 3.10 Traceability & Artifact Model

- Artifacts capture before/after snapshots per deliverable; mandatory for mission completion.

### 3.11 Failure Handling & Abort

- Regression protocol, abort protocol defined.

### 3.12 Mission Creation = Execution

- Mission creation is the primary “work” humans/agents do. To start a change, describe it as data (Types, Templates, Actions, CodeTypes, Protocols, Mission records). No additional manual execution steps are expected once the dictionary entry is recorded.
- Protocols MUST treat those structural records as executable plans: when a mission enters `:in-progress` the runtime reads the definitions, runs the declared actions/tests/docgen/system-map refresh, and drives the mission to completion without bespoke scripting.
- Agents are therefore responsible for: (1) ensuring every structural artifact is modeled in the dictionary, (2) citing relevant `:spec/section` entries, (3) wiring doc/system-map templates. After that, the system enforces invariants and produces evidence automatically.

### 3.13 Mission Instantiation Protocol

- Before any sandbox spins up, a mission derived from a WorkPlan node MUST run `:protocol/mission-instantiation`. The protocol enforces §§3.3–3.6 (readiness, scope, locks), §4.7 (CodeType/test coverage), §5.1 (automation-first), §6.2 (sandbox evidence), and §9 (audit trail).
- **Step 1 – `:action/mission.from-plan`:** Reads the validated WorkPlan file + selected PlanNode, copies the declared resources/test-scope, and writes `mission-plan-binding.edn` so reviewers know exactly which coverage rows justify the mission.
- **Step 2 – `:action/mission.lock.resolve`:** Normalizes those resources into `:mission/resource` rows, checks for conflicts (failing the protocol if another mission owns a resource), and records `locks-request.edn` ahead of any changes.
- **Step 3 – `:action/mission.sandbox.prepare`:** Invokes the existing env bootstrap + git branch actions to produce `sandbox-manifest.edn`, linking the binding + lock evidence directly to §6.2 sandbox isolation.
- Mission instantiation now rejects WorkPlans that lack generator provenance (`:plan.generation/id`, `:plan.generation/status :plan.generation.status/validated`, heuristics id/log-path) or steward approval (`:work.plan/status :work.plan.status/approved`), blocking manual/ungoverned plan inputs.
- Missions only transition to `:ready` after this protocol succeeds; failed steps block the mission and the resulting artifacts give §9 traceability for remediation.
---

## 4. Dictionary & App Meta-Model

### 4.1 Common Entity Envelope

- Every entity has type, path, status, version, created/updated metadata.

### 4.2 TypeDefinition & AttributeDefinition

- Invariants on owner types, required attributes, migrations.
- Example EDN shown in Appendix A.

### 4.3 Template Catalog

- TemplateDefinitions must have config schema/execution contract.
- TemplateInstances must validate config before persisting.

### 4.4 Navigation Model

- NavSpace/NavItem definitions with invariants/tests.

### 4.5 App & Workflow Definitions

- AppDefinition fields; WorkflowDefinition + WorkflowStep fields/invariants.

### 4.6 Actions & Protocols

- ActionDefinition/ProtocolDefinition types with validation invariants.

### 4.7 Code Types & Auto-Validation

- `:meta/code-type` defines structural rules and now carries generator metadata (`:code.type/generator`, `:code.type/generator-templates`, `:code.type/generated-artifacts`) so each CodeType cites the templates + artifacts required by §§3.3–3.6, §4.7, §5.1, §5.3.
- `:action/codetype.generate` resolves that metadata, renders templates into the sandbox, writes `.codetype/<ident>.edn` stamps, and appends `codetype-generation.edn` evidence before the mission touches resources; codegen actions must still validate output before writing.
- Planner heuristics infer CodeTypes from mission scope/resource paths, declared artifacts, and risk/change tags; any WorkPlan or mission that names a CodeType outside the catalog is rejected and emits a remediation stub mission so the steward can propose the new entry (§§3.3–3.6, §4.7, §5, §9).
- Mission-standard uses the inferred CodeTypes to run `:action/codetype.generate` before lint/tests and validates paths from mission scope, preventing manual CodeType hints and keeping artifacts deterministic (§§3.3–3.6, §4.7, §5.1).

### 4.8 External Integrations & JS Extensions

- Types for `:external/*`, `:js/component`.
- Governance protocols for JS components and external APIs.

### 4.9 Spec Sections as Data

- `:spec/section` schema, `:action/spec.sync`, `:action/spec.export`.

### 4.10 UI & Interaction Model

#### 4.10.1 Graph-first navigation

- Identity bar, breadcrumb/trail, relationship strip, mini-graph, suggested next steps, command palette.

#### 4.10.2 Progressive disclosure

- Overview band, context panes, activity rail, action toolbar.

#### 4.10.3 Composable workspace

- Pane zones, shared chrome, layouts/perspectives, keyboard controls.

#### 4.10.4 App discovery

- Search-first app launcher with facets, cards, relationship panel, collections.

#### 4.10.5 Calm telemetry

- Global health strip, chips, inline indicators, unified activity timeline, minimal alerts.

#### 4.10.6 Editors in unified shell

- Shared shell, design tokens, editor plug-in contract, mode indicators.

---

## 5. System-for-System: Actions, Protocols, Tooling

### 5.1 Core Actions

- List of minimal actions (env bootstrap, logging, tests, doc/system-map, schema, templates, mission validate/report, spec sync, lock management, deploy) now includes `:action/codetype.generate`, which records codetype-generation.edn + stamp evidence so §§3.3–3.6, §4.7, §5.1, §5.3 traceability survives mission instantiation. The same list now embeds the Agent Gateway wrappers `:action/mission.step.plan`, `:action/mission.step.edit`, `:action/mission.step.tool-run`, and `:action/mission.step.decision`, which accept structured EDN payloads and emit mission-step artifacts so every plan refinement, file edit, lint/test run, or decision references requirements/tests before touching the repo (§§3.3–3.6, §4.7, §5.1, §5.3).
- Agents (human or automated) MUST interact through these gateway actions via the CLI/API helper; direct shell edits, manual `clojure -M:*` invocations, or ad-hoc plan documents are violations. Structured I/O is enforced by `missions/logs/<id>/steps/<timestamp>-*.edn` plus paired Markdown summaries that steward automation can parse.
- `:action/ci.run-profile` executes the dictionary-driven CI profile (lint/tests/codetype) for each edit/merge, while `:action/mission.merge.prepare` and `:action/mission.merge.execute` handle the rebase+CI+merge automation, writing `ci-run.edn`, `merge-prepare.edn`, `merge-log.edn`, or `merge-failure.edn` under `missions/logs/<id>/ci|merge/…` so SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §6.2 have concrete evidence before steward sign-off.

### 5.2 Core Protocols

- Env bootstrap, mission standard, revise/abandon/regression, module evolve, deploy blue/green & canary, spec sync.

### 5.3 Enforcement Mapping

- Each invariant tied to action/protocol/test (see appendix).

### 5.4 Consolidation & Audit Ownership Matrix

Per `plan.md` §11, the steward keeps a single matrix tying governed protocols to the roles accountable for execution and escalation so audits do not repeat ownership discovery. These rows cite the SYSTEM_SPEC clauses that describe each protocol’s obligations:

| Protocol ident | Primary owner | Escalation role | Governing sections |
| --- | --- | --- | --- |
| `:protocol/mission-standard` | `:role/dictionary-engineer` | `:role/steward` | §§3.3–3.6, §5.1 |
| `:protocol/mission-sync` | `:role/dictionary-engineer` | `:role/steward` | §§3.3–3.6, §6.2 |
| `:protocol/spec-intake` | `:role/dictionary-engineer` | `:role/steward` | §§3.3–3.6, §4.1–§4.2, §5.1, §9 |
| `:protocol/work-plan-intake` | `:role/dictionary-engineer` | `:role/steward` | §§3.3–3.6, §4.7, §5.1, §9 |
| `:protocol/mission-instantiation` | `:role/dictionary-engineer` | `:role/steward` | §§3.3–3.6, §4.7, §5.1, §6.2 |
| `:protocol/js-approve` | `:role/steward` | `:role/dictionary-engineer` | §2.2, §4.8, §10.2 |
| `:protocol/js-revoke` | `:role/steward` | `:role/ops` | §2.2, §4.8, §10.2 |
| `:protocol/deploy-blue-green` | `:role/ops` | `:role/steward` | §2.2, §6.3 |
| `:protocol/deploy-canary` | `:role/ops` | `:role/steward` | §2.2, §6.3 |
| `:protocol/deploy-rollback` | `:role/ops` | `:role/steward` | §2.2, §6.3 |

---

## 6. Operational Concerns

### 6.1 Authentication & Authorization

- Session handling, expiring sessions, permission checks for every action/protocol.

### 6.2 Multi-User Isolation

- Unique sandboxes; no shared mutable directories/ports; cleanup actions.
- Agent Gateway enforcement: all plan/edit/tool/decision steps flow through the mission-step actions so structured EDN evidence is created inside `missions/logs/<id>/steps/` instead of raw terminal output. Agents may not bypass the gateway with shell commands; automation polls `dev/agent_gateway.clj` (or the hosted equivalent) for mission context, submits steps, and inspects artifacts. This keeps §§3.3–3.6, §4.7, §5.1, §5.3 compliant traces even when multiple engineers share infrastructure.
- Gateway-only cutover: manual shell paths (including `dev/run_mission.clj`) are frozen. All mission starts (ad-hoc or scheduled) must call `dev.agent-gateway run-mission` with the issued context bundle under `missions/logs/<id>/context-bundle.edn` or the MCP/HTTP equivalent. The cutover flag (see `missions/logs/M-20251121-803/cutover.edn`) is set to `:gateway/mode :gateway-only` so §§2.1–2.2, §§3.3–3.6, §5, §6, and §9 always apply before any repo mutations or CI runs.
- Each time new files are recorded, the gateway immediately calls `:action/ci.run-profile` with the mission-type CI profile and writes `ci-run.edn` plus per-step logs so every edit is accompanied by lint/test/codetype outputs that cite SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §6.2. Before a mission can transition to `:mission.status/done`, the runtime reruns the CI profile again and attaches the evidence to the transition log.
- Merge automation is orchestrated via `:protocol/mission-merge` (prepare → execute). `:action/mission.merge.prepare` rebases onto main, reruns the CI profile, and stores `merge-prepare.edn`/`ci-run.edn` under `missions/logs/<id>/ci|merge/<timestamp>/`. `:action/mission.merge.execute` either emits `merge-log.edn` (clean merge) or `merge-failure.edn` (conflict), providing auditable traces for §§3.3–3.6, §5.1, §5.3, §6.2.

### 6.3 Deployment Strategies

- Blue/green and canary strategies with invariants; environment entities; deploy protocols.

### 6.4 Observability & Monitoring

- Logging requirements, metrics, alerts feeding incident missions.

### 6.5 Performance & Scalability

- Performance budgets per area, caching/invalidation actions, load tests.

### 6.6 Data Safety, Backups & Secrets

- Snapshot actions, recovery missions, secrets stored outside Datomic.

### 6.7 Operational Missions

- Ops tasks treated as missions with the same traceability/testing requirements.

---

## 7. Documentation & System Map

- Spec mirror, doc templates, system map data model, generation actions, UI surfaces, search, access control, testing.

---

## 8. Graph-Native Versioning & Evolution

### 8.1 Graph-Native Versioning Layer

- Every spec intake run now ends with `:action/version.snapshot-spec`, which writes `missions/logs/<id>/versioning/spec/<timestamp>/version-snapshot.edn` containing the normalized spec body plus digests for `spec-validation.edn/.md` and the publish log. This snapshot is mandatory evidence for §§3.3–3.6, §4.1, §4.7, §5.1, and §8.1 because it proves which requirements/tests/contracts were approved.
- `:action/version.snapshot-plan` follows plan publish and records the WorkPlan DAG/coverage alongside the steward markdown. It also links back to the latest spec snapshot via `:version.link.relation/spec->plan`, so requirement coverage carries the validated spec id/version without human stitching.
- `:action/version.snapshot-mission` runs after merge execution. It pulls the final mission report and merge-log.edn, hashes both, and links the resulting snapshot back to the plan snapshot (`:version.link.relation/plan->mission`). The artifact list always includes spec validation, plan coverage, mission report, and merge log entries so reviewers can cite §§3.3–3.6, §4.7, §5.1, §6.2, and §8.1 directly.
- `intuition.versioning.runtime/snapshot-history` and `trace-requirement->snapshots` expose the graph at runtime. Pulling history for a spec/plan/mission lists every snapshot plus its artifacts, while tracing a requirement returns the spec→plan→mission path so audits can prove end-to-end coverage without trawling logs.
- Snapshot files live under `missions/logs/<mission>/versioning/<type>/<timestamp>/version-snapshot.edn` and the dictionary schema now treats `:version/snapshot`, `:version/link`, and `:version/artifact` as first-class entities, so downstream docs/system-map tooling can render the lineage automatically.

### 8.2 Evolution Protocols & Modularization

- Module definition, evolution missions, feature flags/staging, dependency tracking, registries, automation.

---

## 9. Spec-to-Mission Pipeline

- Spec as data, spec→protocol binding, mission generation action, human role (curate spec/priorities), where reasoning happens.
- Mission generation MUST emit fully-formed structural definitions. The moment a spec section is translated into a mission, the Dictionary should already know the Types/Actions/CodeTypes involved so that execution is an automatic replay of those records. Human reasoning happens at spec interpretation time; machines replay the plan.
- The `:protocol/spec-intake` pipeline (spec.capture → spec.validate → spec.publish) records normalized specs plus `spec-validation.edn/.md` + publish logs so §§3.3–3.6, §4.1–4.2, §4.7, §5.1, and §9 evidence exists before backlog generation.
- The `:protocol/work-plan-intake` pipeline (generator-only) delegates to `:action/spec.plan.generate` + `:action/work-plan.validate`; manual WorkPlan capture/publish entrypoints are removed. The pipeline writes normalized WorkPlans under `resources/work-plans/`, runs coverage/DAG/resource validators that prove every requirement/test contract is mapped (SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §9), and records `work-plan-validation.edn/.md` plus publish logs so stewards can audit which obligations were satisfied or violated.
- The automation scheduler (see `intuition.scheduler.core` + `dev.scheduler`) polls `missions/list-ready-missions`, orders candidates by §3.6 priority, and launches governed agent sessions via the Agent Gateway `run-mission` CLI/MCP against `missions/logs/<mission-id>/context-bundle.edn`. Queue tags, mission priority, and lock requirements from the bundle/mission are forwarded in the gateway payload; the scheduler validates the bundle mission id, reads the auth token from `:auth/token-path` for watermarking (redacted in artifacts), and honors bundle `:retry` (bounded attempts + backoff) so §§2.1–2.2, §§3.3–3.6, §5, §6, §9, and §11 are enforced before any edit. Successful launches write `scheduler-run.edn` under `missions/logs/<mission-id>/scheduler/`; repeated failures or a mission that stays `:mission.status/ready` produce `scheduler-failure.edn` for steward escalation.

---

## 10. Extended Mechanisms

### 10.1 Agent-authored Missions

- Mission suggestion entities, scoring, states, protocols; steward approval required.

### 10.2 JS & External Integration Governance

- Security invariants, approval protocols, tests.

### 10.3 Mission Suggestion Engine

- Signals, scoring function, thresholds, invariants.

### 10.4 Action/Protocol Lifecycle

- Lifecycle states, deprecation/removal protocols, tests.

### 10.5 UI Theming & Layout

- Theme/layout entities, invariants, validation actions/tests.

---

## 11. Examples

### 11.1 TypeDefinition & AttributeDefinition

```edn
{:db/id "attr-mission-status"
 :entity/type :meta/attribute
 :entity/path "/system/attributes/mission/status"
 :entity/name "Mission status"
 :attribute/ident :mission/status
 :attribute/value-type :db.type/keyword
 :attribute/cardinality :db.cardinality/one
 :attribute/required? true
 :attribute/options {:enum #{:mission.status/draft
                              :mission.status/ready
                              :mission.status/in-progress
                              :mission.status/revision
                              :mission.status/awaiting-review
                              :mission.status/abandoned
                              :mission.status/done
                              :mission.status/archived}}
 :attribute/owner-type :mission/record
 :attribute/description "Lifecycle state of a mission."}

{:db/id "type-mission-record"
 :entity/type :meta/type
 :entity/path "/system/types/mission/record"
 :entity/name "Mission"
 :type/ident :mission/record
 :type/category :category/system
 :type/attributes [:mission/id :mission/title :mission/summary :mission/status
                   :mission/priority :mission/prerequisites :mission/scope
                   :mission/work-tracks :mission/deliverables]
 :type/meta {:primary-table "/dictionary/views/mission/table"
             :primary-detail-view "/dictionary/views/mission/detail"
             :tags #{:sfs :change-control}}}
```

### 11.2 ActionDefinition Example

```edn
{:db/id "action-test-run-suite"
 :entity/type :meta/action-definition
 :entity/path "/system/actions/test/run-suite"
 :entity/name "Run test suite"
 :action/ident :action/test.run-suite
 :action/kind :action.kind/test
 :action/code-ref 'intuition.sfs.actions.test/run-suite
 :action/config-schema {:required [:suite-id]
                        :type {:suite-id :keyword
                               :timeout-ms :long}}
 :action/outputs-schema {:type {:status :keyword
                                :tests-run :long
                                :tests-failed :long
                                :logs :string}}
 :action/required-permissions [:perm/run-tests]
 :action/meta {:description "Run a named test suite and record results."}}
```

### 11.3 Mission Template Skeleton

```markdown
# M-YYYYMMDD-### – <Title>

## Summary
<2–3 sentences>

## Category
:mission.category/<...>

## Scope
- Entity path prefixes…
- Code namespaces…
- Environments…
- Impact…

## Prerequisites
- M-YYYYMMDD-### – reason

## Deliverables
- `code/file` – `src/...`
- `dictionary/entity` – `/system/...`
- `test/suite` – `:test.suite/...`

## Work-Type Matrix
| Kind | Required | Notes |
|------|----------|-------|
| planning | yes | ... |
| code | yes | ... |
| test-functional | yes | ... |

## Acceptance
- Run tests via `:action/test.run-suite`…
- Docgen/system-map actions…
```

---

## 12. Next-Phase Execution Plan

### Phase 1 – Core Meta-Types & SfS Skeleton

1. **M-01 – Seed core meta-types & attributes**
   - Deliverables: TypeDefinitions/AttributeDefinitions for meta-entities.
   - Tests: `test/schema-core-metadata`.
   - Success: Can transact/query core meta-entities.

2. **M-02 – Implement ActionDefinition + ProtocolDefinition runtime**
   - Deliverables: Action/Protocol definitions + execution engine.
   - Tests: `test/actions-contract`, `test/protocols-sequence`.

3. **M-03 – Implement mission lifecycle**
   - Deliverables: `:mission/record` type, mission actions.
   - Tests: `test/mission-validation`, `test/mission-state-machine`.

4. **M-04 – Env bootstrap & log-step**
   - Deliverables: `:action/env.bootstrap`, `:action/log.step`.
   - Tests: `test/env-isolation`, `test/log-step-links`.

### Phase 2 – System Map & Docs

5. **M-05 – System map minimal**
   - Deliverables: system map types, refresh action.
   - Tests: `test/system-map-no-dangling-edges`.

6. **M-06 – Docgen for types & missions**
   - Deliverables: doc templates/actions.
   - Tests: `test/docgen-type-app`.

### Phase 3 – Mission Tooling

7. **M-07 – `list-ready-missions` & `start-mission`**
   - Deliverables: REPL entrypoints, start protocol, locks.
   - Tests: `test/mission-ready-filtering`.

8. **M-08 – Mission report & approval**
   - Deliverables: report/approve/archive protocols.
   - Tests: `test/mission-report-requires-tests`.

### Phase 4 – Governance & Deployments

9. **M-09 – JS & external API governance**
   - Deliverables: types + approval protocols.
   - Tests: `test/js-security-sandbox`, `test/external-api-call-paths`.

10. **M-10 – Deployment strategy (blue/green)**
    - Deliverables: deployment entities + protocols.
    - Tests: `test/deploy-blue-green`.

### Phase 5 – Mission Suggestions

11. **M-11 – Mission suggestion engine**
    - Deliverables: suggestion entities, actions, protocols.
    - Tests: `test/mission-suggestions-scores`.

Each mission moves the system closer to full self-hosting. All work MUST flow through missions defined by this spec; ad-hoc edits are forbidden.

---

## Appendix A – TypeDefinition & AttributeDefinition Examples

(See Examples above.)

## Appendix B – Invariant → Enforcement Mapping

- P1.1 – `:action/config.scan`
- P3.2 – `:action/mission.report`, `protocol/mission-approve`
- D4.2 – `:action/template.validate-config`
- M3 – `:action/lock.acquire` / `release`
- etc. (full table maintained in Datomic as `:invariant/definition`)

--- 
