# North Star – Self-Describing App Factory

## 1. Purpose

This project builds a **self-describing app factory**:

- At the **center** is a **dictionary**: a structured, queryable model that holds *all the truths* about:
  - Data structures
  - Apps and their UI
  - Permissions and users
  - Missions, tests, deployments
  - As much of the system itself as is practical

- Around the dictionary is an **app platform**:
  - Apps are **defined in the dictionary** (not hardcoded).
  - Apps can **edit the dictionary** (under constraints).
  - Apps can build other apps, including specialized tooling for themselves.

- The system is built to be used primarily by **agents** under human supervision:
  - Agents extend the data model.
  - Agents change code.
  - Agents create new apps inside the system.

The end state: a system where **almost everything that matters is explicit, inspectable and modifiable**, and where **agents can safely evolve the ecosystem** at scale.

---

## 2. Core principles

1. **Everything is a system**  
   - There are systems for:
     - Missions
     - Documentation
     - Testing
     - Deployment
     - Logging
     - Navigation
   - Each system has a clear specification and is itself represented in the dictionary whenever it makes sense.

2. **Dictionary as source of truth**  
   - If something can be modelled in the dictionary without killing performance, it **should** be.
   - Code is generic and minimal; behaviour is driven by dictionary entries.
   - Over time, the dictionary describes:
     - Itself (meta-schema)
     - All apps
     - Permissions, roles, configs
     - Missions, test cases, deployment rules

3. **Self-description and introspection**  
   - There is always at least one **system map** that is true:
     - Overview of types, relationships, apps, endpoints, missions.
   - Agents and humans can query this map to understand the system without reading all the code.
   - Documentation is auto-generated wherever possible from the dictionary and system map.

4. **Agent-first development**  
   - The primary “developers” are agents constrained by:
     - Missions
     - SOP
     - Tests
     - System specification (this document set)
   - Humans:
     - Define missions and accept work.
     - Control concurrency and avoid race conditions.
   - Agents:
     - Work on one mission at a time.
     - Produce traceable worklogs.
     - Run tests and verify flows before marking missions as done.

5. **Safety, correctness, and traceability before convenience**  
   - Every mission:
     - Has explicit dependencies.
     - Has explicit acceptance criteria and tests.
     - Leaves an audit trail via worklogs and commit history.
   - The production system should **always be in a working state**.
   - Illegal or inconsistent data should be **hard or impossible** to enter via UI.

6. **Scalability in apps and users**  
   - The platform is designed for **thousands of apps** and **many users**:
     - Navigation and search scales well.
     - Apps can be created, versioned, and retired without global rewrites.
     - Users can have personalized configuration and app sets.

7. **Performance and observability as first-class concerns**  
   - Performance is not an afterthought:
     - We reason about performance characteristics as we design the data model.
     - We add instrumentation early.
   - Logging, metrics, and error-handling are part of the **initial architecture**, not bolted on later.

8. **Incremental, mission-driven evolution**  
   - We move from blank slate → North Star through small, composable missions.
   - Each mission changes a small part of the system but always updates:
     - Tests
     - Docs
     - System map (where applicable)

---

## 3. Target end-state (what “North Star” means)

At the North Star point, the system has these properties:

1. **Dictionary completeness (practical, not absolute)**  
   - All critical “truths” about the platform live in the dictionary:
     - Types, fields, relationships
     - Apps, pages, views, workflows
     - Permission model and roles
     - User-specific configuration
     - Mission and testing metadata
   - The dictionary describes its own structure (meta-types).

2. **App factory**  
   - New apps are defined by creating dictionary entries, not by writing new bespoke controllers/templates.
   - Specialized apps exist to:
     - Browse and edit the dictionary.
     - Generate forms, tables, and navigation.
     - Build new apps faster (“apps that build apps”).

3. **Agent-native workflow**  
   - Agents can:
     - Discover open missions and dependencies.
     - Inspect system maps and docs programmatically.
     - Run tests and deployment previews.
   - The mission system ensures:
     - No two agents accidentally step on each other’s toes.
     - Every change is traceable to a mission and worklog.

4. **Multi-user and permissions**  
   - Multiple users can:
     - Log in.
     - Have individualized settings, app lists, and access rights.
     - Own private apps that others cannot edit.
   - Permissions are dictionary-driven and composable.

5. **Deployment and update safety**  
   - There is a standard pipeline:
     - Local agent work → tests → staging → production.
   - Agents perform updates from a local machine, but:
     - Production is always in a working state.
     - Rollbacks and migrations are well-defined.
     - Tests + health checks guard deployments.

6. **Observability and performance**  
   - The system produces:
     - Structured logs.
     - Metrics (latency, error rates, resource usage).
     - Alerts when key guarantees are violated.
   - Performance bottlenecks can be found and addressed systematically.

7. **Self-documentation**  
   - Documentation and system maps are generated from the dictionary where possible:
     - Type diagrams.
     - App dependency graphs.
     - Mission histories.
   - Static docs (like this one) describe the concepts and guarantees; generated docs describe the current state.

---

## 4. In-scope vs out-of-scope

**In scope (North Star boundary)**

- Dictionary-driven app definition and UI.
- Mission/agent workflow for evolving the system.
- Multi-user auth and permissions.
- Logging, metrics, and error handling.
- Deployment strategy that keeps production working.
- Extensibility for:
  - Advanced UI (JS, rich components).
  - External API integrations.

**Out of scope (for now)**

- Being a general-purpose LLM orchestration platform.
- Fully generic no-code builder for literally any arbitrary system in one shot.
- Perfect formal verification of all behaviour.

The system *should* be able to grow in those directions later, but we don’t design for them upfront.

---

## 5. Roles and personas

1. **System Owner (you)**  
   - Defines missions and approves work.
   - Curates and occasionally edits the specification and North Star.
   - Controls access and high-level architecture decisions.

2. **Agents**  
   - Execute on missions within the constraints of:
     - This document.
     - The system spec.
     - The SOP.
   - Cannot invent new missions out of thin air in the early stages (later: they can propose missions).

3. **App Builders (inside the platform)**  
   - Future human users who use the system to define new apps via UI.
   - They may never touch raw code; they operate purely through the dictionary-driven interfaces.

4. **End Users of Apps**  
   - Use the apps that were defined in the dictionary.
   - Have permissions and configurations assigned by app builders/system owner.

---

## 6. Architectural shape (high-level)

The system decomposes into three layers:

1. **Core Platform Layer**
   - Dictionary engine (types, entries, queries).
   - Auth and permissions.
   - Mission and worklog storage.
   - Logging and metrics.
   - Deployment support (migrations, seeds).

2. **App Builder Layer**
   - Apps that:
     - Display and edit dictionary entries.
     - Compose pages, forms, tables, and flows.
     - Define validations and constraints.
   - Internal tools for:
     - Viewing system maps.
     - Browsing missions and test suites.
     - Tuning performance.

3. **Runtime App Layer**
   - Apps created using the builder:
     - Domain-specific CRUD (and beyond CRUD) systems.
     - Specialized dictionary editing tools.
     - Custom apps for specific users or teams.

The **same dictionary model** underlies all three layers.

---

## 7. Guiding constraints for all design decisions

- Prefer **data and configuration** over custom logic.
- Prefer **simple, composable primitives** over clever abstractions.
- Maintain **testable seams** between systems.
- Never compromise **traceability**:
  - Who changed what, when, under which mission, with which tests?
- Aim for **monotonic improvement**:
  - Each step leaves the system better observed, better specified, or better tested than before.

---

## 8. How this document is used

- This North Star is the **top-level alignment document**.
- The **System Spec** explains *how* we satisfy these principles.
- The **Roadmap** explains *when and in what order* we move towards this state.
- Missions are derived from:
  - Gaps between current state and this North Star.
  - Items on the Roadmap.
  - Invariants in the System Spec that are not yet enforced.

Changes to this document are rare and deliberate.