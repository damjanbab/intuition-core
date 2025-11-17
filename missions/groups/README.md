# Mission Groups

Mission groups (mini-projects) bundle related missions under a single brief. Each group file describes:

- **Name & ID** – e.g., `Phase-1-Dict-Skeleton`.
- **Objective** – What the group aims to deliver.
- **Scope & constraints** – Shared tech decisions, invariants, or guardrails.
- **Dependencies** – Other groups or missions that must complete first.
- **Mission list** – IDs + titles included in the group.
- **Review plan** – Criteria to mark the group as complete and triggers for the review meeting.
- **Findings** – Once the group completes, capture lessons learned here.

Create briefs as Markdown files inside this directory (e.g., `missions/groups/Phase-1-Dict-Skeleton.md`). Mission files should reference the group name in their metadata (`- **Group:** Phase-1-Dict-Skeleton`).
