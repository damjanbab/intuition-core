That’s exactly where we want to land. The endgame is:

Specs as data → validated target specs stored in Datomic.
CodeTypes as templates → each CodeType knows how to generate its namespace, tests, docs, etc.
Protocols glue it together → once a mission plan is validated, the system instantiates the CodeTypes (generates files), runs validators/tests, and merges automatically.
Right now we’re halfway there: CodeTypes exist, but they’re only documentation. To evolve toward “code from data,” we need to:

Finish the spec intake + mission-planning protocols so every requirement is structured and validated before work begins.
Augment CodeTypes with generators (e.g., template files or transformations) so creating a new CodeType automatically produces the skeleton code/tests/docs.
Link CodeTypes to missions/specs – when a mission references a CodeType, the protocol can auto-generate the needed files, run validators, and ensure they’re tied back to the spec.
Replace ad-hoc Git with graph-aware versioning – store generated code artifacts (or their references) in Datomic so we can track versions semantically, not just by commit hash.
Once those pieces are in, you’ll be able to describe a feature in the spec format, have the system generate the necessary CodeTypes/namespaces, and let agents focus on refining data rather than hand-writing glue code. So the short answer: we want code-from-data from the start, but we need to finish the spec + CodeType pipeline first, then the generation layer becomes the natural next step.