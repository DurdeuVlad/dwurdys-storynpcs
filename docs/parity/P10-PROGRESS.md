# P10 — Authoring hub, AI patch plans, creator docs (P10-1..P10-3)

Status: `IN-REVIEW` for P10-1, P10-2, and P10-3 (local implementation only; no issue is closed by this progress note).
Evidence: `P10DomainTest` and `CreatorDocsExamplesTest` exist. The full-suite result in the prior snapshot is stale; current verification is recorded separately.

## P10-1 — Unified authoring hub (#88)

`editor/hub/AuthoringHub` is the headless model; `client/gui/AuthoringHubScreen`
is the thin rendered adapter, opened by `/storynpcs hub` (permission 2) through
`ClientboundHubOpenPayload`. The hub is a navigation shell — it mutates nothing
itself; every panel routes to the canonical server-authoritative open/save
paths the domain already owns.

- 17 typed panels cover the required domains; `coversAllDomains()` is a set
  equality check so a renamed or dropped panel fails the suite.
- Deterministic case-insensitive search, bounded pagination, wrap-around
  keyboard navigation (Up/Down/Tab/PageUp/PageDown/Enter), per-panel preview
  line, and a rendered diagnostics list (`FieldError` = schema path + message +
  repair hint) all live on the model and are JUnit-tested.
- `GuiParityCatalog` maps all 149 target GUI classes to a StoryNPCs screen,
  a hub panel, an explicit improved equivalent, a #150 player surface, or a
  COMPONENT_NA widget-internals marker — verified row-for-row against
  `target-surface-manifest.json` in `GuiParityCatalogTest`.
- `npcWorkflow()` gives the canonical single-NPC authoring sequence.
- Route strings are honest: COMMAND routes name registered literals only
  (test scans `StoryNpcsCommands` for the literal), so nothing dispatches to a
  command that does not exist.

Disclosed residuals:

- The YAML-free workflow spine is identity/display/AI/combat-scalars/
  dialogue/quest/faction (screens + commands). Equipment, inventory, job,
  script, and template enrichments are YAML-family + canonical-command
  routes — the hub names the family path instead of fabricating an editor.
- Undo/redo lives inside the routed editors (e.g. the dialogue graph editor),
  not in the hub itself — the hub's only state is navigation + diagnostics.
- Minimum-resolution behaviour is verified at the layout math level
  (9 rows at the 427x240 logical viewport = 854x480 physical @ scale 2);
  no live-Minecraft render evidence exists under the no-live-runtime
  constraint.

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
