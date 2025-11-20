# Type – :external/api

*Spec sections:* §4.8, §6, §10.2

*Doc categories:* dictionary, spec

*Doc template:* `doc.type`

*Template instance:* `doc.type.external-api`

## Type Summary

- **Name:** External API
- **Ident:** `api`
- **Path:** /system/types/external/api
- **Category:** `security`
- **Description:** Outbound integration contract (SYSTEM_SPEC.md §§4.8, 6, 10.2).

## Attributes

| Ident | Value Type | Cardinality | Required? | Description |
|-------|------------|-------------|-----------|-------------|
| `ident` | `keyword` | `one` | yes | Keyword identity for governed integrations (SYSTEM_SPEC.md §§4.8, 6). |
| `base-url` | `string` | `one` | yes | HTTPS entrypoint recorded for approval/tracing (SYSTEM_SPEC.md §6.6 & §10.2). |
| `endpoints` | `string` | `many` | yes | Serialized endpoint definitions with method/scope data (SYSTEM_SPEC.md §§4.8, 10.2). |
| `risk-profile` | `keyword` | `one` | yes | Links to :risk/profile for operational policies (§6, §10.2). |
| `name` | `string` | `one` | yes | Display name for the integration (§§4.8, 6). |
| `provider` | `string` | `one` | yes | Owning team/vendor (SYSTEM_SPEC.md §6.1). |
