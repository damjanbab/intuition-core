# System Specification – Self-Describing App Factory

> This document defines the concrete behaviour, data model, and non-functional guarantees of the system described in `01_NORTH_STAR.md`.

---

## 1. Overview

### 1.1 Goals

- Provide a dictionary-centric platform where:
  - Most behaviour is driven by data in the dictionary.
  - New apps are defined in the dictionary, not in bespoke code.
  - Agents can safely evolve code and data via missions.

- Ensure:
  - Multi-user, permissioned access.
  - Strong validation at the edges (illegal states are hard to represent).
  - Observability and performance from the beginning.

### 1.2 Non-goals

- Being a universal no-code builder for every possible UI pattern.
- Eliminating all need for code changes (the platform is extensible but not magic).
- Providing arbitrary LLM/AI features; agents are external.

---

## 2. Core concepts

### 2.1 Dictionary

**Definition**  
The dictionary is a collection of **entries** stored in a database. Each entry has:

- A stable identifier (e.g. `dict-id`).
- A **type** (e.g. `type`, `field`, `app`, `view`, `permission`, `user`, `mission`).
- A map of **attributes** defined by its type.
- Metadata (created/updated timestamps, created-by, etc.).

**Requirements**

- Every dictionary entry:
  - Conforms to a type with a schema.
  - Can be referenced by other entries.
- Types themselves are dictionary entries (“meta-types”).

### 2.2 Types and fields

A **Type** describes a shape of data; a **Field** describes a single attribute.

Minimal required properties:

- `type` entry:
  - `id` (string or keyword)
  - `name` (human readable)
  - `kind` (e.g. `entity`, `value`, `system`)
  - `fields` (references to field entries)
  - `constraints` (validation rules, e.g. uniqueness, required fields)

- `field` entry:
  - `id`
  - `name`
  - `data-type` (string, integer, boolean, enum, ref, etc.)
  - `cardinality` (one, many)
  - `required?` (boolean)
  - `default` (optional)
  - `validation-rules` (expressions or references)
  - `ui-hints` (form component type, e.g. select, checkbox, code editor)

Types needed early:

- `type` (meta type)
- `field`
- `app`
- `page`
- `view` (e.g. table, form)
- `action` (e.g. create, update, run-flow)
- `permission`
- `role`
- `user`
- `mission`
- `worklog`
- `test-case`
- `config` (per-user and global)

### 2.3 Apps

An **App** is a dictionary entry describing:

- Identity:
  - `app/id`, `app/name`, `app/slug`
- Ownership & access:
  - `app/owner` (user or system)
  - `app/permissions` (required permissions to see/use)
- Composition:
  - `app/pages` (list of page entries)
  - `app/nav-order` (how pages are ordered)
- Behaviour:
  - `app/start-page`
  - `app/config` (any app-specific configuration)

Apps do **not** contain code directly; they refer to pages, views, and actions defined separately.

### 2.4 Pages and views

A **Page** is:

- A route (`/path`), an owning app, and a set of views.

A **View** is:

- A concrete UI component (form, table, detail view, dashboard widget, etc.).
- Bound to:
  - A **type** (the data it operates on).
  - A **view kind** (table, form, graph, etc.).
  - A **query** (how it finds its data).
  - A **presentation config** (columns, layout, filters).

Early view kinds:

- `table` – list of entities with columns, sorting, filtering, row actions.
- `form` – create/update a single entity.
- `detail` – read-only display of an entity.
- `navigation` – lists of links, app switchers, etc.

### 2.5 Actions and workflows

An **Action** represents a transformation or operation, for example:

- Creating/updating/deleting entries.
- Invoking an external API.
- Running a multi-step workflow.

Each action:

- Has an `input` schema.
- Has a `precondition` (e.g. permission check, type check).
- Has a `side-effect` implementation (code) and description in the dictionary.
- Has an `audit` policy (what gets logged).

Workflows:

- Compose multiple actions into a flow (with branching and validation).
- Are also stored in the dictionary as entries referencing actions.

---

## 3. Users, authentication, and permissions

### 3.1 Users

A **User** has:

- Identity:
  - `user/id`, `user/username`, `user/email`
- Auth:
  - Credentials (implementation-specific; not necessarily stored in the dictionary).
  - Link to an external auth provider if any.
- Relations:
  - `user/roles` (references to role entries)
  - `user/configs` (references to config entries)

### 3.2 Roles and permissions

**Permissions** are dictionary entries:

- `permission/id`
- `permission/key` (e.g. `dictionary.admin`, `app.X.edit`)
- `permission/description`

**Roles** are dictionary entries:

- `role/id`
- `role/name`
- `role/permissions` (list of permission entries)

Effective permissions for a user = union of permissions from all their roles.

### 3.3 Access control model

- Every app has required permissions to:
  - View the app.
  - Access specific pages.
  - Perform actions.
- Permissions for dictionary editing are **highly constrained**:
  - e.g. `dictionary.admin`, `dictionary.types.edit`, `dictionary.apps.edit`.

**Guarantee:**  
If a permission does not exist in the dictionary, it should be treated as **invalid** and rejected at validation time.

---

## 4. Missions and agents

*(High-level here; details live in `20_MISSIONS_AND_AGENTS.md`)*

### 4.1 Mission state machine

Missions have:

- `State`: `open` → `in_progress` → `done` or `cancelled`
- `Owner`: optional agent name
- `Archived`: flag + metadata

Rules:

- Only one agent may own a mission at a time.
- Only agent owning a mission can move it from `in_progress` to `done`.
- Archiving is done after human acceptance.

### 4.2 Worklogs

Each mission has:

- A dedicated worklog file (outside the dictionary initially).
- Each agent iteration is:
  - Timestamped.
  - Includes intent, changes, tests run, notes.

Later, missions/worklogs can be mirrored to the dictionary to appear in the system map.

### 4.3 Agent interface

Agents rely on:

- The mission board file.
- The SOP.
- Commands/tools:
  - Run tests.
  - Run specific flows.
  - Search dictionary and code.
  - Update docs and system maps.

---

## 5. Testing and quality

### 5.1 Test layers

- **Unit tests:**
  - Dictionary manipulation.
  - Validation logic.
  - Permission evaluation.

- **Integration tests:**
  - App rendering from dictionary.
  - Endpoints and workflows.
  - Mission lifecycle.

- **End-to-end tests:**
  - Critical flows in main apps (dictionary editor, app builder, mission UI).

### 5.2 Rules

- No mission can be marked `done` unless:
  - All tests specified in its **Test checklist** have passed.
  - Any failing tests are explicitly acknowledged and accepted by the human.

- Introduce new tests whenever:
  - A new invariant is introduced.
  - A regression is fixed.

---

## 6. Deployment and ops

### 6.1 Environments

Minimum environments:

- `local` – agent development.
- `staging` – pre-production verification.
- `production` – always-working system.

### 6.2 Deployment pipeline

- Agent works locally on a mission.
- Agent runs tests.
- Changes get pushed through CI:
  - Run full test suite.
  - Optionally run synthetic end-to-end checks.
- On success:
  - Deploy to staging, then production.
- Rollback strategy:
  - Keep previous version ready.
  - Migrations must be reversible or have forward-compatible strategies.

---

## 7. Observability and performance

### 7.1 Logging

- Structured logs for:
  - HTTP requests.
  - Actions and workflows.
  - Permission checks (especially failures).
  - Dictionary changes.

- Logs include:
  - Timestamp
  - User/agent (if applicable)
  - Mission ID (when relevant)
  - Correlation/trace IDs

### 7.2 Metrics

Key metrics:

- Request latency and error rates.
- Dictionary query performance.
- App rendering time.
- Throughput of mission-related automation (test runs, deployments).

### 7.3 Error handling

- Clear error boundaries:
  - User-facing errors (validation, auth).
  - System errors (exceptions, timeouts).
- Errors should:
  - Be logged with context.
  - Avoid leaking sensitive data.

---

## 8. Documentation and system map

### 8.1 Static docs

- This document set is the canonical specification.
- Docs are updated as part of missions when behaviour or guarantees change.

### 8.2 Generated docs

The system will generate:

- Diagrams of types and relationships from the dictionary.
- Lists of apps and their pages/views.
- Lists of permissions and who has them.
- Mission histories and status.

### 8.3 System map API

- Provide machine-readable endpoints for:
  - Current dictionary schema.
  - App definitions.
  - Permission model.
  - Mission status.

Agents use this API to orient themselves and propose future missions.

---

## 9. Extensibility

### 9.1 Custom code

- Some behaviour will always live in code:
  - Complex validation.
  - Performance-critical operations.
  - Integration with external systems.

- The dictionary references these code points:
  - e.g. `validation/handler`, `action/runtime-fn`.

### 9.2 External integrations

- Dictionary entries describe:
  - External APIs.
  - Credentials (securely stored).
  - Mappings between external schemas and internal types.

---

## 10. Open questions (to be refined as we go)

- Exact persistence technology (e.g. Datomic vs SQL).
- How much of the mission system should be mirrored into the dictionary vs remain in files.
- How far to push code generation vs stable generic code paths.

These questions are not blockers for initial implementation; they will be resolved via missions and updated here.
