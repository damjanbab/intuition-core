# System Spec 2 – Target Story (Draft)

We are fixing the end-state reasoning pipeline in plain language. Statements below capture intent; remaining gaps will be filled as we refine.

## Target Story (what the system must do)

1) **Entrypoint & Request Types**  
   - One intake only: scheduler → gateway with a bundle. No parallel channels.  
   - Request types we care about first: anything needed to modify/extend the system itself (code changes, refactors, analysis, pipelines, docs). New request types are added by changing the system through the same path.

2) **Standard Journey per Request**  
   - Mandatory steps for every request: intake → clarify/qualify → plan → choose/define recipe → generate → validate/tests → summarize/deliver. Nothing skips these.  
   - Outputs always returned: artifacts produced, test results, a short summary with risks/assumptions, and the bundle/manifest path for evidence.  
   - Implication: by fixing the journey, every request is auditable; adding steps later is additive, not breaking.

3) **Where Reasoning (AI) Is Allowed/Expected**  
   - AI is one-shot only, never interactive.  
   - Allowed slots: (a) clarify the brief, (b) propose plan refinements, (c) draft a new recipe/template, (d) draft code/tests/docs when no template exists yet, (e) summarize outcomes.  
   - Default stance: AI is optional in each slot; if off or fails, the run either uses safe defaults or stops and asks for a better brief/template.  
   - Implication: non-determinism is boxed into specific points; failure to get an AI answer never causes silent guessing.

4) **Deterministic Work & Templates**  
   - Principle: systematize before execution; anything repeatable becomes a template.  
   - Day-one template set (minimum to self-modify): (i) add/extend service logic + tests + doc stub, (ii) add/extend data/query + safety checks + tests, (iii) add/extend CLI/gateway command + tests + doc, (iv) add/extend analytics/report, (v) add/extend pipeline step/workflow.  
   - New patterns: by default we let AI draft the template (one-shot), then lock it in; if AI is off, the request waits until a template exists. No free-form execution without a recipe.  
   - Every template must declare inputs, generated files, required tests, docs stub, validation checks, and side-effect manifest.  
   - Implication: scalability comes from growing the catalog; AI accelerates template birth, but execution stays deterministic once a template lands.

5) **Safety, Evidence, and Traceability**  
   - Every run produces: manifest, logs, test results, AI self-report (if used), and semantic links (so future runs don’t need AI to rediscover context).  
   - Hard stops: unclear scope after clarify, missing template for the requested pattern, failed validations/tests, or missing required outputs. No guessing past these gates.  
   - Implication: we trade extra steps for reliability and faster future runs (context is baked into artifacts).

6) **Routing and Configuration**  
   - Single entrypoint; internal routing by request type. AI classification is optional; defaults come from request metadata.  
   - Flags/modes: per-stage AI on/off, dry-run vs apply.  
   - Implication: one front door keeps operations simple; flags let us tune cost/risk without new entrypoints.

7) **Boundaries and Out-of-Scope**  
   - Out-of-scope is minimal by design; anything needing external secrets or context we don’t hold is deferred until provided.  
   - Implication: the system aims to expand in all directions, but will stop rather than fabricate missing external context.

Notes: Robustness is favored over brevity—more steps are acceptable if they keep “always works” and scalable growth intact.

## Recipe Contract (the “IR” for work)
- Metadata: id/version, owner, stability tier (experimental/beta/ga/frozen), intent tags, required capabilities; immutable versioning for determinism.  
- Inputs: typed fields with schema and defaults; required context (files, repo state, runtime), constraints, and secrets-handling flags.  
- Outputs: structured result schema (status, artifacts, patches/diffs, metrics), side-effect manifest (files touched, commands run), and a human-readable summary.  
- Plan: ordered or DAG steps built from a small allowed op set (LLM call, tool call, map/reduce, branch, evaluate code, retry/backoff, select-best). Each step declares bindings, tools, budgets.  
- Validation: preflight checks, invariant assertions after steps, output schema validation, and reproducibility hints (seeds, locked tool versions).  
- Limits: budgets for tokens/time/files touched; allowed tools; sandbox profile (read-only/workspace/full).  
- Error handling: retry policy, fallback recipe ids, escalation paths.  
- Audit: full trace (inputs, decisions, tool calls, hashes of artifacts) to replay.

## Catalog Layers and Governance
- L0 primitives: core ops (llm_call, tool_call, map/reduce, branch, eval_code, retry/backoff, select_best). Rarely change.  
- L1 patterns: generic flows built only from L0 (e.g., generate-then-critique, map-over-files, rag-retrieve-then-answer). Parameterized; no domain specifics.  
- L2 domain recipes: bind one pattern with domain prompts/tools/validations (e.g., code.refactor.typescript). May not change structure.  
- L3 extensions: thin variants that extend a domain recipe (style/policy tweaks) without changing structure or IO shape.  
- Composition rule: higher layers depend downward only; no lateral hidden deps; detect cycles.  
- Registry: versioned with stability tiers; conformance tests per recipe to ensure only declared primitives are used. Capability gating: each recipe declares required tools; dispatcher matches by intent tags + capabilities + policy.  
- Promotion: experimental → beta (limited use/monitored) → ga/frozen after deterministic runs and sign-off. AI-suggested recipes must pass the same checks.

## Planner Flow When No Recipe Fits
- Router filters recipes by tags/language/constraints; optional classifier scores candidates.  
- If no recipe meets threshold: call one-shot planner AI to draft an ephemeral recipe in the same schema, with strict guards (limited tools, low budgets, no high-side-effect ops unless declared).  
- Run structural validation and guardrails; if invalid, use fallback generic pattern or stop.  
- Execute deterministically; record traces. Ephemeral recipes are not persisted; clusters of successful ones can be synthesized later into a real recipe via the promotion path.

## AI Usage Rules
- One-shot only; never interactive loops.  
- Allowed to: clarify a brief, propose plan refinements, draft new recipes/templates, draft code/tests/docs when no template exists, summarize outcomes.  
- Mandatory nowhere by default; if off/fails, either use defaults where safe or stop with a clear request for more info/template.  
- AI-drafted persistent recipes must auto-generate contract, plan, tests, pass conformance and lint, and follow the promotion tiers.  
- No high-side-effect execution from AI drafts without passing validations and approvals.

## Determinism, Policy, and Telemetry
- Determinism levers: seeded randomness, canonical ordering of inputs/files, hash-locked tool/model versions, explicit environment capture, forbidden undeclared tool calls/writes.  
- Policy layer: branching rules, approvals, and test matrices injected as policies, not baked into recipes.  
- Telemetry: step traces, side-effect manifests, AI self-reports (confidence, assumptions, uncertainties), and semantic links so later runs don’t need AI for context recovery.  
- Hard stops: unclear scope, missing template for pattern, failed validations/tests, missing required outputs, or missing policy approvals.

## Day-One Primitives and Patterns (for self-modification)
- Primitives: llm_call, tool_call, map/reduce, branch, eval_code, retry/backoff, select_best/vote.  
- Patterns: generate-then-critique, simple single-step, map-over-files, retrieve-then-answer (for analysis), safe-edit with lint/test, refactor-and-test loop.  
- Domain recipes to ship first (self-mod work): service logic change, data/query change, CLI/gateway command change, analytics/report change, pipeline step/workflow change—each bound to a pattern and carrying required tests/docs/validation.  
- Extensions: style/policy skins over the domain recipes (e.g., org-specific refactor rules) without changing structure.
