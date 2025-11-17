# Canon – Immutable Alignment for the Self-Describing App Factory

This document records the truths that every other artifact derives from. Anything not contradicted here may evolve, but the canon can only change via deliberate, explicitly approved missions.

---

## 1. Mission of the Platform

We are building an ecosystem where:

- A **dictionary** describes itself, every app, every workflow, and every operational guarantee that matters.
- **Agents**, under human orchestration, evolve both code and dictionary through tightly scoped **missions** with auditable histories.
- The platform can eventually express any UI, workflow, or integration by manipulating dictionary entries rather than rewriting bespoke code.

The project optimizes for long-term scalability, introspection, and safety over short-term velocity.

---

## 2. Canonical Principles

1. **Everything is a system** – Documentation, mission flow, testing, deployment, permissions, UI primitives, observability, and navigation each have explicit specs, data, and tooling.
2. **Dictionary is the source of truth** – If information can live in the dictionary without destroying performance, it must. Code is generic scaffolding executing dictionary data.
3. **Self-description before convenience** – There is always at least one system map and documentation set that is correct and queryable by humans and agents.
4. **Mission-driven change** – Every modification to code or data is the result of an approved mission with dependencies, acceptance criteria, tests, and worklogs.
5. **Safety, correctness, and observability first** – Illegal states are hard to represent, deployments keep production healthy, and performance/logging/metrics are designed-in from day one.
6. **Multi-user, configurable, permissioned** – Users have individualized workspaces, private apps, and configuration entries in the dictionary.
7. **Agents are primary builders** – Humans orchestrate to prevent race conditions; agents implement missions end-to-end, including docs, tests, and verification.

---

## 3. Technology Commitments (Initial)

These choices anchor early implementation decisions. Any change requires an explicit mission.

- **Data layer**: Datomic (or equivalent) for graph-centric dictionary storage and history.
- **Server runtime**: Clojure with Ring as the HTTP entry point.
- **Routing**: Prefer bidi; evaluate alternatives only through missions.
- **UI rendering**: Hiccup for HTML, Garden for CSS, HTMX for progressive enhancement and graph-friendly interactions.
- **Client-side behaviour**: Minimal bespoke JS; richer components must still be describable via dictionary entries.
- **Observability**: Structured logging and metrics are mandatory in every new subsystem; integrations can evolve but may not be skipped.

---

## 4. Invariants

- **Single source of truth**: Docs, system spec, and mission data never drift from the implemented system; discrepancies trigger missions.
- **Traceability**: Every change references mission ID, worklog, and test results.
- **Reversibility**: Deployments and migrations include rollback or forward-compatible plans.
- **Performance budget**: Any new feature documents expected query/latency characteristics.
- **Automation bias**: Prefer auto-generated docs, tests, and system maps wherever possible.

---

## 5. Change Control

- Modify this canon only via missions approved by the system owner.
- When canon changes, update dependent docs (North Star, Spec, Roadmap) and create follow-up missions if implementation gaps appear.
- Agents must halt work and request clarification if they encounter ambiguity touching canonical principles.

---

## 6. References

- `01_NORTH_STAR.md` – Destination state.
- `02_SYSTEM_SPEC.md` – Concrete requirements.
- `03_ROADMAP.md` – Phased delivery plan.
- `20_MISSIONS_AND_AGENTS.md` – Mission governance.
- `21_AGENT_SOP.md` – Operational steps for agents.

