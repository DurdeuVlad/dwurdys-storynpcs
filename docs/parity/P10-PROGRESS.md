# P10 — Authoring hub, AI patch plans, creator docs (P10-1..P10-3)

Status: P10-1 remains `IN-REVIEW`; P10-2 and P10-3 remain `IN-REVIEW` (local implementation only; no issue is closed by this progress note).
Evidence: `P10DomainTest` and `CreatorDocsExamplesTest` exist. The full-suite result in the prior snapshot is stale; current verification is recorded separately.

## P10-1 — Unified authoring hub (#88)

`editor/hub/AuthoringHub` is currently a headless model only. It is not connected to the editor screens; the former static client singleton was removed to comply with managed-lifecycle rules.

- The model defines typed panels and deterministic panel search/pagination.
- Its in-memory screen state and error list are not yet used by editor screens.
- The full field matrix, undo/redo, keyboard navigation, viewport layout, revisioned saves, and rendered diagnostics are not implemented.

## P10-2 — AI-generated content / patch plans (#89)

`authoring/ai/` — the contract for assistant-generated content:

- `SchemaBundle`: versioned schema + cross-reference surface handed to the
  generator (fields, enums, families, sample values) so output is grounded in
  the real format, not hallucinated keys.
- `PatchPlan`: deterministic, idempotent operation list (`opId`,
  `baseRevision`, path + value), dry-runable with zero writes.
- `PatchPlanValidator`: allowlisted operation paths only, source-location
  references on every diagnostic (`PATCH_*` codes), dependency analysis
  (creates-before-references, cycles rejected), permission-context checks.
- The production command currently exposes dry-run validation only. There is no patch-plan apply path; deterministic apply ordering, source-location references, capability enforcement, and rollback remain open.
- Unsupported fields are explicit diagnostics, never silently dropped.

## P10-3 — Creator docs, examples, migration guidance (#90)

`docs/creator/`:

- `QUICKSTART.md` — 5-step walkthrough building a multi-role NPC (guard +
  trader) from YAML only.
- `MIGRATION_GUIDE.md` — accepted inputs, explicit unsupported fields, repeat
  modes, and the diagnostic codes a migrator will hit.
- `examples/` — `guard_npc.yaml`, `merchant_guard.yaml`,
  `guard_dialogue.yaml`, `shopkeeper_quest.yaml`.

`CreatorDocsExamplesTest` is intended to parse the shipped examples through the production `YamlDefinitionLoader` and validate the guard dialogue. These docs do not certify target-runtime parity; that remains blocked by the no-live-Minecraft constraint.

## Known residuals

- Hub/AI/docs are contracts + headless models; the rendered NeoForge screen
  layer is intentionally thin and not covered by GUI-rendering tests (no live
  MC testing permitted).
- CustomNPCs `.dat` import remains #91 scope (P11-1), flagged in the migration
  guide.
