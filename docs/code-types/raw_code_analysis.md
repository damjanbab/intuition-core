# Raw Code Analysis & CodeType Algorithm

## Introduction
The steward’s §4.7 mandate in `SYSTEM_SPEC.md` requires every behavior primitive to advertise a `:meta/code-type` so future codegen actions can validate outputs before persisting artifacts. The surrounding phase plan (§§12.1–12.4) ties these code types to SfS growth: Phase 1 bootstraps dictionary/meta models, Phase 2 formalizes system-map/doc links, and Phase 3+ wire mission tooling + governance. This document gives the “raw feed” that Phase 3 missions can distill into official CodeType definitions: (1) an exhaustive inventory of all runtime modules, resources, tests, and mission notes; (2) a prescriptive algorithm to convert that inventory into computable `CodeType` data, including validator extraction, dependency wiring, and doc/system-map integration.

## Remediation Updates (M-20251121-608)
- Added CodeDefinitions for `intuition.versioning.runtime`, `intuition.analytics.runtime`, `intuition.scheduler.core`, `test/versioning_snapshot_test.clj`, `test/analytics_runtime_test.clj`, and the sample work-plan validator so §8.1/§11 lineage + analytics coverage is explicit.
- Security gating now requires `:permission/security.approve` for JS/API approvals with §2.1–§2.2/§6/§10 watermarks written into approval artifacts; Agent Gateway steps embed the same watermark in step logs.
- Mission start forces `:test.suite/mission-start` execution against declared `:mission/tests`, matching SYSTEM_SPEC §5/§6 expectations and recording the result in start events.
- `dev/scheduler` triggers the analytics runtime after each run, copying reports into `missions/logs/<mission>/analysis/` and labelling them `:analytics.source/scheduler` for §11 visibility.
- Action execution logs are truncated/summarized before Datomic writes (codetype/report payloads capped ~4k chars) to avoid “Item too large” failures while keeping identifiers, counts, and artifact paths.

## Discovery Process
I scanned every namespace/resource under `/home/dami/intuition-core` using only reproducible commands so future stewards can replay the crawl:

- `cd /home/dami/intuition-core && pwd` – confirmed repo root per steward instructions.
- `find src -type f`, `find resources -type f`, `find dev -type f`, `find test -type f`, `find missions -type f`, `find docs -type f` – enumerated every code/test/doc path across src/, resources/, dev/, tests, generated docs, and mission logs.
- `rg` is unavailable in this sandbox, so file contents were read with `sed -n 'start,endp' …` and `cat` for all namespaces listed below.
- Parsed `SYSTEM_SPEC.md` (esp. §4.7 Code Types & Auto-Validation plus §§12.1–12.4 for phase context) via `sed -n '190,260p'` and `grep -n 'Phase'`.
- Sampled mission log bundles (`missions/logs/M-01.md`, `M-02.md`, and `M-04_notes.md`) to capture prior deliverables/spec citations.
- Opened generated mission doc artifacts under `docs/generated/missions/*` to confirm doc runtime wiring.
- Inspected `deps.edn` to understand runtime/test aliases and dependencies.

The following inventory captures every artifact touched during the crawl.

## Codetype Validation Runtime

Mission M-20251120-102 introduced `:action/codetype.validate`, a governed action that reads `resources/dictionary/code_types.edn`, runs each CodeDefinition’s declared validators, and writes `missions/logs/<mission>/codetype-validation.edn` with the touched paths + §4.7/§5.1 citations. The runtime now wires this step into `:protocol/mission-standard` immediately after lint/tests, and mission reports must attach the resulting artifact alongside the standard lint/test evidence so the steward can confirm CodeType coverage.

## Inventory of Behavior Primitives
Each entry lists: primitive/namespace (or resource), file, responsibilities, dependencies, and how it should project into CodeType metadata (attributes, validators, spec references). `Spec refs` cite clauses embedded in comments/data; `Deps` mention namespaces/actions/protocols already imported.

### Runtime Namespaces (`src/`)
| Primitive | File | Responsibilities | Dependencies | CodeType Projection |
| --- | --- | --- | --- | --- |
| `spec.importer` | `src/spec/importer.clj` | Parses `SYSTEM_SPEC.md` headings, derives parent/slug/content, and ingests sections into Datomic (per §4.9). | `clojure.java.io`, `clojure.string`, `datomic.client.api`, Java SHA utils. | CodeType should expose attributes for `:spec/id`, `:spec/title`, `:spec/level`, `:spec/parent`, `:spec/protocols`, `:spec/content-hash`; validators enforce Markdown heading uniqueness and SHA1 integrity. |
| `intuition.datomic` | `src/intuition/datomic.clj` | Dev-local Datomic helpers (client, create/delete DBs, ensure DB). | `datomic.client.api`, `clojure.java.io`. | Candidate `CodeType :component/datomic-client` with attrs `:system-name`, `:storage-dir`, `:default-db-name`; validation requires repo-local `data/` path. |
| `intuition.dictionary` | `src/intuition/dictionary.clj` | Loads dictionary EDN bundles, installs schemas for actions/protocols/logs, seeds data. | `clojure.edn`, `clojure.java.io`, `datomic.client.api`. | CodeType `:dictionary/schema-seed` capturing vectors `:schema/action`, `:schema/protocol`, `:schema/action.execution`, `:schema/protocol.run`; validators check EDN availability and that invariants/locks default to vectors. |
| `intuition.docs.runtime` | `src/intuition/docs/runtime.clj` | Renders doc templates (type + mission) into Markdown/EDN; enforces template/type existence and spec citations. | `clojure.edn`, `clojure.java.io`, `clojure.string`; uses doc templates + meta-types resources. | CodeType `:doc/runtime` with attributes `:doc/template`, `:doc/template-instance`, `:doc/spec-sections`, `:mission` or `:type` payload; validators ensure slug safety and sections align with §4.9. |
| `intuition.sfs.missions.state-machine` | `src/intuition/sfs/missions/state_machine.clj` | Pure mission validation + transition enforcement (scope EDN parsing, work track/tests/deliverables uniqueness, allowed transitions, context requirements). | `clojure.edn`, `clojure.set`, `clojure.string`. | CodeType `:mission/state-machine` capturing allowed statuses, work tracks, transition matrix, requirement keywords; validators enforce EDN scope map, non-blank tests/deliverables, spec cites §§3.1–3.5. |
| `intuition.sfs.missions.runtime` | `src/intuition/sfs/missions/runtime.clj` | Datomic-backed lifecycle runtime: seeds dictionary + missions, manages locks/tests/docs/system-map actions, writes logs/events/report artifacts. | `clojure.java.io`, `clojure.set`, `clojure.string`, `datomic.client.api`, `intuition.*` modules, actions runtime. | CodeType `:mission/runtime` with attrs for mission schema, action orchestration, log/report metadata; validators ensure repo-root artifact paths and doc/system-map/test configs exist before transitions (§§3,5,7). |
| `intuition.sfs.schemas` | `src/intuition/sfs/schemas.clj` | Central `clojure.spec` definitions for actions, docgen, locks, missions, reports, system-map. | `clojure.spec.alpha`, `clojure.string`. | CodeType `:spec/schema-catalog` mapping spec keywords → predicate forms; auto-validation harness extracts spec requirements for each action/protocol. |
| `intuition.sfs.protocols.runtime` | `src/intuition/sfs/protocols/runtime.clj` | Executes protocol step graphs with placeholder resolution, lock/work-track enforcement, action dispatch, run logging. | `clojure.edn`, `clojure.set`, `clojure.tools.logging`, `clojure.walk`, `datomic.client.api`, `intuition.sfs.actions.runtime`. | CodeType `:protocol/runtime` storing step DSL (config placeholders, conditions), lock requirements, instrumentation hooks; validators ensure placeholder ops limited to `:context/:protocol/:state` and work-track coverage (§§4.6,5.2). |
| `intuition.sfs.actions.handlers` | `src/intuition/sfs/actions/handlers.clj` | Pure-ish implementations for env bootstrap, lock acquire/release, test runner, docs sync, docgen. | `clojure.java.io`, `intuition.docs.runtime`; Java time. | CodeType `:action/handler` per action ident; attributes capture input config keys, repo-relative path enforcement, deterministic outputs; spec refs from resources (e.g., `:spec/p3.4`). |
| `intuition.sfs.actions.runtime` | `src/intuition/sfs/actions/runtime.clj` | Generic executor: fetches definitions, checks permissions/specs, resolves handler symbol, logs execution. | `clojure.set`, `clojure.spec.alpha`, `clojure.tools.logging`, `datomic.client.api`. | CodeType `:action/runtime` with fields `:action/ident`, `:config-spec`, `:output-spec`, `:permissions`; validators ensure spec presence and execution log schema matches `resources`. |
| `intuition.sfs.log.step` | `src/intuition/sfs/log/step.clj` | Worklog writer: enforces repo-relative evidence/artifacts, ensures lock token, writes Markdown + Datomic record. | `clojure.edn`, `clojure.java.io`, `clojure.string`, `datomic.client.api`, `intuition.datomic`, `intuition.sfs.env.bootstrap`. | CodeType `:action/log-step` enumerating required attributes (mission/agent/step/deliverable/evidence/artifacts) + repository constraints (§§3.4,9.1). |
| `intuition.sfs.env.bootstrap` | `src/intuition/sfs/env/bootstrap.clj` | Sandbox allocator: deterministic directories, port locking, lock token registration, cleanup. | `clojure.edn`, `clojure.java.io`, `clojure.string`; Java IO, NIO. | CodeType `:env/bootstrap` capturing sandbox path schema, lock token file format, deterministic port range; validators check sanitized fragments and repo-root boundaries (§§6.2,7). |
| `intuition.sfs.system-map.runtime` | `src/intuition/sfs/system_map/runtime.clj` | Builds system-map nodes/edges from dictionary entities, enforces no dangling nodes/edges, provides refresh action wrapper. | `clojure.tools.logging`, `datomic.client.api`. | CodeType `:system-map/runtime` with node/edge schema attributes, dictionary entity sources, invariants referencing §§4.1,4.10. |
| `intuition.dictionary.seed` | `src/intuition/dictionary/seed.clj` | CLI entrypoint for seeding dictionary schema/data. | `clojure.java.io`, `intuition.datomic`, `intuition.dictionary`. | CodeType `:dictionary/seed-command` detailing CLI args, ensures idempotent schema loads referencing log lines in `missions/logs/M-01.md`. |
| `intuition.dictionary.meta-types` | `src/intuition/dictionary/meta_types.clj` | Validates + seeds TypeDefinitions/AttributeDefinitions bundle per M-01 spec citations. | `clojure.edn`, `clojure.java.io`, `clojure.set`, `datomic.client.api`; Java PushbackReader. | CodeType `:meta/type-bundle` enumerating `:type-spec-justification`, stringification rules, base schema; validators ensure required sections exist (§§4.2,11.1). |

### Resource Bundles (`resources/dictionary/*.edn`)
| Primitive | File | Responsibilities | Dependencies | CodeType Projection |
| --- | --- | --- | --- | --- |
| Action catalog | `resources/dictionary/actions.edn` | Declares SfS mission-standard actions with invariants/permissions/specs/handlers. | Handlers resolved via `intuition.sfs.actions.handlers` or env/system-map runtimes. | CodeType `:action/definition` per entry; attributes map to spec keys; reference spec clauses like `:spec/p3.1`. |
| System-map action extension | `resources/dictionary/actions_system_map.edn` | Adds `:action/system-map.refresh`. | `intuition.sfs.system-map.runtime/refresh-action`. | Extend action CodeType to include `:action/tags [:action.tag/system-map]`, spec refs `§4.10`. |
| Env/log actions | `resources/dictionary/actions_env.edn` | Provides env bootstrap + log step definitions that call the env bootstrap ns + log step ns. | `intuition.sfs.env.bootstrap/bootstrap!`, `intuition.sfs.log.step/log-step!`. | CodeType ensures `:action/meta` spec refs and notes additional permissions `:permission/missions.log`. |
| Protocol catalog | `resources/dictionary/protocols.edn` | Defines `:protocol/mission-standard` step graph referencing actions. | Action runtime, `intuition.sfs.protocols.runtime`. | CodeType `:protocol/definition` capturing ordered steps, config placeholders, lock semantics. |
| Mission catalog | `resources/dictionary/missions.edn` | Contains mission statuses/tracks/queue tags and sample mission records referencing spec sections (§3.*). | Mission runtime, doc templates. | CodeType `:mission/record` with attributes for statuses/tracks/deliverables/tests; includes `:entity/path` for doc linking. |
| Doc templates | `resources/dictionary/doc_templates.edn` | Template definitions + instances for documenting types/missions with spec sections. | Docs runtime. | CodeType `:doc/template` describing config schema, categories, spec references. |
| System-map meta-types | `resources/dictionary/system-map.edn` | Type + attribute definitions for graph nodes/edges with spec refs (§§4.1,4.10). | Meta-types seeding, doc runtime. | CodeType `:system-map/type` enumerating attributes, enums, spec citation list. |
| Protocol missions/system map extras | `resources/dictionary/missions.edn` (doubled as action seeds) & `meta-types.edn` (already covered) – ensure mission statuses align with runtime validations. | | |

### Dev Helpers (`dev/`)
| Primitive | File | Responsibilities | Dependencies | CodeType Projection |
| --- | --- | --- | --- | --- |
| `dev.run-mission` | `dev/run_mission.clj` | Runs mission lifecycle start + transition for sample mission, prints outputs. | `intuition.sfs.missions.runtime`. | CodeType `:dev/script` referencing `:mission/id`, `:agent/id`, action configs; used for doc/test harness. |
| `dev.run-protocol` | `dev/run_protocol.clj` | Seeds dictionary and runs `:protocol/mission-standard` with instrumentation printing events. | `intuition.datomic`, `intuition.dictionary`, `intuition.sfs.protocols.runtime`. | Another `:dev/script` code type capturing sample context/perms for regression harnesses. |

### Tests (`test/`)
| Primitive | File | Responsibilities | Dependencies | CodeType Projection |
| --- | --- | --- | --- | --- |
| `actions-contract-test` | `test/actions_contract_test.clj` | Ensures config/permission/output validation & execution logs. | `datomic.client.api`, `intuition.sfs.actions.runtime`, `support.datomic`. | CodeType `:test/contract` describing observed invariants for action runtime (must fail on invalid config/perms). |
| `docgen_type_app_test` | `test/docgen_type_app_test.clj` | Verifies docgen actions produce Markdown/EDN payloads. | `intuition.sfs.actions.runtime`, doc runtime, `support.datomic`. | CodeType `:test/docgen`. |
| `env_isolation_test` | `test/env_isolation_test.clj` | Validates sandbox isolation + cleanup. | `intuition.sfs.env.bootstrap`. | CodeType `:test/env`. |
| `log_step_links_test` | `test/log_step_links_test.clj` | Ensures worklogs link deliverables/locks and missing lock tokens are rejected. | `intuition.sfs.env.bootstrap`, `intuition.sfs.log.step`. | CodeType `:test/worklog`. |
| `mission_state_machine_test` | `test/mission_state_machine_test.clj` | Tests runtime transitions, worklog requirements, action orchestration. | `intuition.sfs.missions.*`, `intuition.sfs.log.step`, `support.datomic`. | CodeType `:test/mission-runtime`. |
| `mission_validation_test` | `test/mission_validation_test.clj` | Validates mission metadata invariants (scope, tracks, prereqs). | Mission state machine. | CodeType `:test/mission-schema`. |
| `protocols_sequence_test` | `test/protocols_sequence_test.clj` | Ensures protocol order, branch gating, dangling locks detection. | `intuition.sfs.protocols.runtime`, `support.datomic`. | CodeType `:test/protocol`. |
| `schema_core_metadata_test` | `test/schema_core_metadata_test.clj` | Validates meta-type bundle invariants + seeding idempotency. | `intuition.dictionary.meta-types`. | CodeType `:test/meta-type`. |
| `system_map_no_dangling_edges_test` | `test/system_map_no_dangling_edges_test.clj` | Ensures system-map refresh rejects dangling nodes/edges. | `intuition.sfs.system-map.runtime`. | CodeType `:test/system-map`. |
| `test/support/datomic` | helper | Provides `with-test-conn`; seeds dictionary per test. | `intuition.datomic`, `intuition.dictionary`. | CodeType `:test/support`. |

### Docs & Mission Logs
| Primitive | File | Responsibilities | Dependencies | CodeType Projection |
| --- | --- | --- | --- | --- |
| Generated docs | `docs/generated/missions/m-20251117-001.{md,edn}` | Output of doc runtime referencing mission info + spec sections. | `resources/dictionary/doc_templates.edn`, missions EDN. | CodeType `:doc/artifact` capturing `:doc/template`, `:doc/spec-sections`, artifact paths for doc sync actions. |
| Mission log M-01 | `missions/logs/M-01.md` | Records deliverables for meta-type bootstrap referencing §§0–5 (Phase 1). | Cites `src/intuition/dictionary/meta_types.clj`, `test/schema_core_metadata_test.clj`. | CodeType `:mission/log` to link mission deliverables to code types; attributes include `:mission/id`, `:deliverables`, `:validation-command`. |
| Mission log M-02 | `missions/logs/M-02.md` | Documents action/protocol runtime deliverables referencing §§4.6,5.1. | Points to `resources/dictionary/actions.edn`, `src/intuition/sfs/actions.runtime`. | Another `:mission/log` record bridging missions to code artifacts. |
| Mission worklog sample | `missions/M-04_notes.md` & `missions/logs/m-*/worklog.md` | Showcases env bootstrap + worklog outputs, datomic tx data referencing §3.* | `intuition.sfs.env.bootstrap`, `intuition.sfs.log.step`. | CodeType `:mission/worklog` to trace artifacts + lock tokens + spec references. |

### Configuration
| Primitive | File | Responsibilities | Dependencies | CodeType Projection |
| --- | --- | --- | --- | --- |
| `deps.edn` | repo root | Declares runtime/test aliases and dependencies (datomic, logging, test runner). | Clojure CLI. | CodeType `:build/deps` capturing alias-to-path/deps mapping; ensures doc/test automation uses correct aliases. |

## Algorithm: Deriving Perfect CodeType Definitions
The following procedure operationalizes §4.7 auto-validation for future agents. Each step references commands already exercised above.

1. **Preparation & Workspace Hygiene**
   1. Ensure repo cleanliness (`git status --short`).
   2. Seed dictionary/meta DBs if missing (`clojure -M:dev -m intuition.dictionary.seed`).
   3. Run `clojure -M:test` to capture baseline traces; store output for dependency graph (action/protocol/test coverage).

2. **Inventory Refresh**
   1. Re-run `find src resources dev test missions docs -type f` and hash outputs (e.g., `shasum`).
   2. For each namespace, emit `(ns … (:require …))` forms into a dependency graph (scriptable via `clj -M -e '(clojure.pprint/pprint (requiring-resolve …))'`).
   3. Parse EDN bundles with `(clojure.edn/read)` to capture field schema + spec citations.

3. **Entity Modeling**
   1. Normalize each primitive into a tuple `{ident file kind responsibilities spec-refs}`.
   2. Map tuple fields into candidate CodeType attributes (as illustrated in the inventory tables).
   3. Validate uniqueness of `ident` + `entity/path` to avoid doc collisions (repurpose `ensure-unique!` from `meta_types.clj`).

4. **Validator Extraction**
   1. For runtime namespaces, parse `clojure.spec` or explicit guard clauses; convert them into declarative validator metadata (e.g., `:mission/scope -> {:type :edn/map :required true}`).
   2. For resources, propagate spec references (`:spec/p*`) into CodeType metadata so docgen/system-map know governing clauses.
   3. Link test assertions to validators by scanning `test/` for `thrown-with-msg?`/`is` patterns; embed expected failure messages into CodeType `:validation/messages` for doc generation.

5. **Dependency Graph Wiring**
   1. Build a bipartite graph: runtime namespaces ↔ actions/protocols/tests referencing them. Tooling wish: use `clojure.tools.namespace.dependency` + Datomic query of action/protocol references to auto-generate edges.
   2. For each CodeType, record upstream dependencies (imports, EDN entries) and downstream consumers (protocol steps, tests). Include mission ids if noted in mission logs.
   3. Persist graph as EDN (e.g., `docs/code-types/code-graph.edn`) to feed doc/system-map automation.

6. **Spec Citation Mapping**
   1. Search for `SYSTEM_SPEC.md` references in comments/data (`rg "SYSTEM_SPEC" -n src resources test` once `rg` is available; currently fallback to `grep`).
   2. For each match, attach `:spec/sections` list to the CodeType entry; cross-check with §4.7 compliance.
   3. Use the importer’s parsed sections to validate citations (`spec.importer/ingest!` output) and detect drift (hash mismatch).

7. **Doc/System-Map Integration**
   1. Feed CodeType entries into doc templates (`intuition.docs.runtime`) by generating synthetic template instances for any new type (automation wish: `clj -M -m intuition.docs.runtime --dry-run :template/doc.type …`).
   2. Extend `resources/dictionary/system-map.edn` to include nodes for new CodeTypes; run `:action/system-map.refresh` to ensure no dangling edges.
   3. Record doc artifacts (Markdown/EDN paths) as part of the CodeType metadata so mission transitions can verify docs were generated.

8. **Testing Harness & Verification**
   1. For each CodeType, link to at least one regression test (existing or new). If missing, generate placeholder tests referencing the spec clause.
   2. Execute targeted test suites via `clojure -M:test -n <namespace>` when updating a CodeType to validate invariants quickly.
   3. Capture Datomic execution logs (action + protocol) to confirm runtime validation triggered; map log IDs back to CodeTypes for audit trails.

9. **Automation/Tooling Wishlist**
   - Static namespace dependency extractor (graphviz output) to visualize CodeType relations.
   - Spec citation checker that compares declared `:spec/sections` vs. actual `SYSTEM_SPEC.md` structure (leveraging `spec.importer`).
   - Doc/system-map diff reporter to show which CodeTypes lack generated docs or nodes.

## Open Questions / Next Steps
- Several mission logs (`missions/logs/m-log-*.md`) capture ephemeral worklogs; we need a parser to extract deliverables/tests to enrich CodeType metadata beyond the curated `M-01`/`M-02` samples.
- `resources/dictionary/meta-types.edn` mentions `:meta/code-type` yet no runtime currently publishes such entries. Future missions must decide whether CodeTypes live as new TypeDefinitions or as overlays referencing existing ones.
- Automation for dependency graph extraction is currently manual; investigate integrating `clojure.tools.namespace.dependency` or `kondo` analysis to keep the inventory evergreen.
- Doc templates only cover mission + type docs. Phase 2 spec (§12.2) expects app/system-map docs; CodeType workflow should outline how to materialize those templates before governance requires them.
- Validation harness: we run `clojure -M:test`, but mission transitions (start/transition/report) aren’t executed in CI. Consider scripted dry runs (similar to `dev/run-mission`) for every CodeType change to ensure §3.x lifecycle invariants remain satisfied.
- Determine whether CodeTypes should capture runtime side-effects (files written, ports allocated). Current inventory hints at these but formal schema is pending steward approval.
