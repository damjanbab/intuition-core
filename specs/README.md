# Project Specifications

Structured specs live under `specs/`. The layout separates the reusable schema, the template, and individual project instances so multiple specs can coexist.

```
specs/
  schema.edn              ; field-level documentation / constraints
  project_spec.template.edn ; blank instance without data
  current.edn             ; {:current-spec "intuition-core"}
  instances/
    intuition-core.edn    ; actual project spec data
``` 

## Schema (`schema.edn`)
`schema.edn` exposes `:field-docs`, a map explaining what each path means (e.g., `:vision/summary` must be outcome-focused, capabilities never describe implementation). Meta Agents read this for validation prompts.

## Template
`project_spec.template.edn` is a blank spec without field docs. Meta Agents copy it to `instances/<slug>.edn` when starting new projects.

## Instances
Each spec instance gets its own file under `specs/instances/`. The current active spec is recorded in `specs/current.edn`. Tooling reads that file to know which instance to load by default.

## Lifecycle
1. Meta Agent ensures `instances/<slug>.edn` exists (copying the template if needed) and updates `current.edn` to point at it.
2. During spec intake the agent edits only the instance file, referring to `schema.edn` for field rules.
3. After validation passes and the owner approves, the spec status flips to `:approved`. When a spec is superseded, `current.edn` can point to a different instance.

Because everything is EDN, Datomic/other stores can ingest these files directly later.
