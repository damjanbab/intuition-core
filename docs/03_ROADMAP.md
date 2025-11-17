# Roadmap – From Blank Slate to Self-Describing App Factory

> This roadmap is mission fuel. Each phase defines objectives, exit criteria, and example missions.

---

## Phase 0 – Foundations: Documents and Mission System

**Objective:**  
Create the textual foundation so humans and agents can work without this chat.

**Deliverables:**

- Core docs:
  - `00_CANON.md`
  - `01_NORTH_STAR.md`
  - `02_SYSTEM_SPEC.md`
  - `03_ROADMAP.md`
  - `20_MISSIONS_AND_AGENTS.md`
  - `21_AGENT_SOP.md` (imported/refined)
- Mission board folder (`missions/` with `board/` status files and mission template).
- Git governance spec + enforcement plan (`docs/22_GIT_GOVERNANCE.md` plus referenced scripts/hooks).
- Template for mission worklogs.

**Exit criteria:**

- An agent, given only the repo, can:
  - Understand what the system is supposed to become.
  - Understand how to take a mission and work on it.
  - Create and update missions without ambiguity.

**Example missions:**

- M-YYYYMMDD-001: Create initial docs and commit them.
- M-YYYYMMDD-002: Define mission and worklog templates; wire them into `20_MISSIONS_AND_AGENTS.md`.
- M-YYYYMMDD-003: Translate agent SOP into the new doc structure.

---

## Phase 1 – Core Platform Skeleton (Dictionary + Minimal Runtime)

**Objective:**  
Stand up a minimal but working platform with a tiny dictionary and basic UI.

**Deliverables:**

- Repo structure (e.g. `src/`, `test/`, `docs/`, `resources/`).
- Running backend with:
  - Health endpoint.
  - Simple server-rendered UI framework (even basic HTML + minimal JS).
- Minimal dictionary implementation:
  - Types: `type`, `field`, `app`, `page`, `view`.
  - Basic CRUD API for dictionary entries.
- Hardcoded “Dictionary Browser” app to:
  - List types and entries.
  - View a single entry.

**Exit criteria:**

- Local `dev` mode:
  - Agent can run the server.
  - Agent can add a new type and see it in the dictionary browser.
- Basic tests:
  - At least one unit test for dictionary operations.
  - At least one integration test for the dictionary browser.

**Example missions:**

- Implement dictionary storage abstraction.
- Implement minimal HTTP server and templating.
- Implement the “Dictionary Browser” app.

---

## Phase 2 – Dictionary-Defined Apps

**Objective:**  
Move from hardcoded app/UI to apps defined in the dictionary itself.

**Deliverables:**

- App model implemented:
  - `app`, `page`, `view` types fully defined in the dictionary.
- Table and form view kinds:
  - Table: list entities, support columns and simple filters.
  - Form: create/edit single entities with validation.
- Generic renderer:
  - Reads app/page/view entries from the dictionary and renders them.

**Exit criteria:**

- At least one “real” app is defined purely in the dictionary (e.g. the Dictionary Browser itself).
- Adding a new app or page does not require new backend code.
- Tests cover:
  - Rendering of dictionary-defined apps.
  - Validation of app definitions (e.g. nav order, required fields).

**Example missions:**

- Define `app`, `page`, `view` types in the dictionary.
- Implement `table` and `form` view renderers.
- Migrate hardcoded Dictionary Browser into dictionary-defined form.

---

## Phase 3 – Missions, Agents, and Internal Tools

**Objective:**  
Make the system easier for agents to operate and evolve.

**Deliverables:**

- Mission board integrated into the system:
  - `mission` type in the dictionary (even if the source of truth is still a file).
  - Read-only mission viewer app for agents.
- Worklog surfacing:
  - Optional: index worklogs to reference them in the dictionary or system map.
- Basic “System Map” page:
  - Lists types, apps, and mission relationships.

**Exit criteria:**

- An agent can:
  - See missions in the UI.
  - Navigate from a mission to affected apps/types.
- The mission system is **not** just a file anymore; parts of it are visible in the platform.

**Example missions:**

- Implement `mission` type and mission viewer app.
- Implement system map v1 (e.g. index of types and apps).
- Create tooling to link missions and code changes (even via conventions).

---

## Phase 4 – Multi-User, Permissions, and Per-User Config

**Objective:**  
Support multiple users with individualized access and configuration.

**Deliverables:**

- User, role, permission, and config types implemented in the dictionary.
- Auth layer:
  - Simple but secure login.
  - Session management.
- Permission enforcement:
  - Apps and pages check permissions.
  - Dictionary editing constrained by roles.
- Per-user configuration:
  - Default app, theme, or layout preferences.
  - User-specific “app sets” (e.g. favourites).

**Exit criteria:**

- Two users with different roles see different apps/pages.
- One user can create an app that another user cannot edit (or even see, depending on design).
- Tests verify:
  - Permission enforcement.
  - Per-user config retrieval.

**Example missions:**

- Implement auth and user management.
- Implement permission check middleware.
- Implement user config model and a settings UI.

---

## Phase 5 – Deployment, Observability, and Performance

**Objective:**  
Get to a point where the system can live on a cloud server and stay healthy under change.

**Deliverables:**

- CI pipeline:
  - Runs tests.
  - Builds and deploys to staging and production.
- Telemetry:
  - Structured logging.
  - Basic metrics dashboard.
- Performance budget and instrumentation for critical flows:
  - Dictionary queries.
  - App rendering.
  - Mission tools.

**Exit criteria:**

- Production instance running in the cloud.
- Updates performed by agents from local → staging → production.
- Dashboards showing at least:
  - Error rates.
  - Request latency.

**Example missions:**

- Set up CI/CD.
- Instrument core flows with metrics.
- Set up log aggregation and error alerting.

---

## Phase 6 – Advanced App Capabilities and Integrations

**Objective:**  
Go beyond CRUD and basic tables/forms.

**Deliverables:**

- JS extensibility:
  - Components that allow richer interaction while still declared in the dictionary.
- External API integration model:
  - Represent external systems and mappings in the dictionary.
- Higher-level workflows:
  - Multi-step wizards.
  - Event-driven behaviours.

**Exit criteria:**

- At least one app that:
  - Uses custom JS components defined via dictionary-configured views.
  - Integrates with an external API.
- Clear documentation on how to add new “view kinds” and integration types.

**Example missions:**

- Implement pluggable JS view kind.
- Implement external API integration for a simple third-party service.
- Define workflow model and implement a multi-step flow.

---

## Phase 7 – Self-Hosting, Self-Modification, and Auto-Documentation

**Objective:**  
Approach the full North Star where the system deeply describes and evolves itself.

**Deliverables:**

- System map v2:
  - Visualizations of dictionary schema, apps, permissions, and missions.
- Auto-generated documentation:
  - Pages that generate docs for:
    - Types and fields.
    - Apps and pages.
    - Permissions.
- Mission suggestion tooling (long-term):
  - Agents + system can propose missions based on:
    - Observability signals.
    - Docs/spec gaps.
    - Test failures.

**Exit criteria:**

- A new contributor (or agent) can:
  - Understand the system architecture primarily via the system’s own UI (system map + generated docs).
  - Navigate from any app/type to its documentation and missions.
- Static docs are still the canonical conceptual source, but the system reflects its current state without manual curation.

**Example missions:**

- Implement system map visualizations.
- Implement doc generation for types/apps.
- Implement small agent-facing “What should I work on next?” surface.

---
