# Type – :mission/record

*Spec sections:* §1.2, §3.1, §3.5, §4.2, §7

*Doc categories:* dictionary, spec

*Doc template:* `doc.type`

*Template instance:* `doc.type.mission-record`

## Type Summary

- **Name:** Mission record
- **Ident:** `record`
- **Path:** /system/types/mission/record
- **Category:** `sfs`
- **Description:** Mission lifecycle definition (SYSTEM_SPEC.md §3 & §1.2).

## Attributes

| Ident | Value Type | Cardinality | Required? | Description |
|-------|------------|-------------|-----------|-------------|
| `id` | `string` | `one` | yes | Mission slug (SYSTEM_SPEC.md §3.1, §1.2). |
| `title` | `string` | `one` | yes | Short mission title (SYSTEM_SPEC.md §3.1). |
| `summary` | `string` | `one` | yes | Mission summary block (SYSTEM_SPEC.md §3.1 & §11.3). |
| `status` | `keyword` | `one` | yes | State machine for missions (SYSTEM_SPEC.md §3.1). |
| `work-tracks` | `keyword` | `many` | yes | Required work-track coverage (SYSTEM_SPEC.md §3.3). |
| `tests` | `string` | `many` | yes | Acceptance tests required by invariants P1–P10 (SYSTEM_SPEC.md §1.2). |
| `category` | `keyword` | `one` | yes | Category to enforce scope planning (SYSTEM_SPEC.md §3.1). |
| `priority` | `keyword` | `one` | yes | Queue priority for mission selection (SYSTEM_SPEC.md §3.6). |
| `queue-tags` | `keyword` | `many` | no | Queue routing markers for §3.6 priority assignment. |
| `protocol` | `keyword` | `one` | yes | Protocol binding enforced by SfS (SYSTEM_SPEC.md §3.5 & §5.2). |
| `protocol-version` | `long` | `one` | yes | Pinned protocol version (SYSTEM_SPEC.md §3.5). |
| `scope` | `string` | `one` | yes | Structured scope record (SYSTEM_SPEC.md §3.2 & §3.10). |
| `prerequisites` | `string` | `many` | no | Upstream missions that must land first (SYSTEM_SPEC.md §3.1). |
| `deliverables` | `string` | `many` | yes | List of required artifacts (SYSTEM_SPEC.md §3.4 & §11.3). |
| `spec-section` | `keyword` | `one` | yes | Traceability to SYSTEM_SPEC.md (SYSTEM_SPEC.md §1.2 & §3.10). |
| `owner` | `keyword` | `one` | yes | Role accountable for the mission (SYSTEM_SPEC.md §2 & §3.6). |
| `js-components` | `string` | `many` | no | List of :js/component entries declared in mission scope (SYSTEM_SPEC.md §§4.8, 6, 10.2). |
| `external-apis` | `string` | `many` | no | Vector of :external/api declarations scoped to the mission (SYSTEM_SPEC.md §§4.8, 6, 10.2). |
