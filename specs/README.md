# Project Specifications

All project definitions live here as structured EDN so agents can read and update them programmatically. Later these facts will flow directly into Datomic, so every document in this directory mirrors the eventual entity model.

## Files

- `project_spec.edn` – the active working spec for the current project.
- `project_spec.template.edn` – blank scaffold Meta Agents copy when spinning up a new project.

Each file conforms to the schema described inside `project_spec.template.edn`. Meta Agents edit these files via code (never manual free-form docs) while interviewing the human owner.

### Lifecycle

1. Meta Agent creates or loads `project_spec.edn` with `:spec/status :draft`.
2. After each interview turn it updates this EDN, re-runs validation, and records issues under `:validation_summary`.
3. Once `:validation_summary.errors` is empty and the owner signs off, Meta Agent flips `:spec/status` to `:approved` and bumps `:spec/version`.
4. Mission planners and workflow registries read only approved specs.

Because the format is already EDN, tomorrow’s Datomic schema can ingest the file as-is (each nested map becomes facts). EOF
