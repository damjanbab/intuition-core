# Type – :js/component

*Spec sections:* §4.8, §6, §10.2

*Doc categories:* dictionary, spec

*Doc template:* `doc.type`

*Template instance:* `doc.type.js-component`

## Type Summary

- **Name:** JS component
- **Ident:** `component`
- **Path:** /system/types/js/component
- **Category:** `security`
- **Description:** Gov model for embedded JS bundles (SYSTEM_SPEC.md §§4.8, 6, 10.2).

## Attributes

| Ident | Value Type | Cardinality | Required? | Description |
|-------|------------|-------------|-----------|-------------|
| `ident` | `keyword` | `one` | yes | Keyword used to reference governed JS bundles (SYSTEM_SPEC.md §§4.8, 10.2). |
| `bundle-path` | `string` | `one` | yes | Repository-relative artifact captured by approvals (SYSTEM_SPEC.md §§4.8, 10.2). |
| `dependencies` | `string` | `many` | yes | NPM/module identifiers declared for the bundle (SYSTEM_SPEC.md §6). |
| `risk-profile` | `keyword` | `one` | yes | Links to :risk/profile so mitigations map back to SYSTEM_SPEC.md §6. |
| `name` | `string` | `one` | yes | Human readable label for the component (§§4.8, 10.2). |
| `description` | `string` | `one` | no | Summary of the embedded bundle and why it exists (SYSTEM_SPEC.md §§4.8, 6). |
| `call-paths` | `string` | `many` | yes | List of allowed JS entrypoints/call chains (SYSTEM_SPEC.md §10.2). |
