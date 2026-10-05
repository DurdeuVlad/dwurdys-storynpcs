# Issue #123 — Markov name generation closeout

Status: `IMPLEMENTED` (decision: clean-room implementation, recorded in `Decision.md` 2026-10-05)

## Decision

**Implement** with clean-room dictionaries — no target code or dictionary text
reused. Target provenance and licensing implications:

- The target bundles `nikedemos.markovnames` (15 classes) plus 19 txt name
  lists under `data/customnpcs/markovnames/`.
- The upstream `markovnames` project and CustomNPCs' bundled lists ship under
  `CC BY-NC` — direct reuse of the target's name lists would import a
  non-commercial-only artifact into this repository's content.
- All StoryNPCs dictionaries are authored fresh for this repository. The
  generator itself is an independent re-implementation (seeded char-level
  Markov walk); the algorithm is not subject to the content license.

## Delivered

- `NameDictionary` (`domain/namegen/`): versioned (`schemaVersion: 1`) bounded
  model — `order` [2,4], ≤512 names, ≤48 chars/name, no control characters,
  namespaced `id` validated at set time.
- `MarkovNameGenerator` (`domain/namegen/`): deterministic — same dictionary +
  seed → same name; independent `Random` instances per call (no shared/global
  RNG state); ≤32 attempts, ≥3-char floor, ≤48-char output cap; empty/degene­rate
  dictionaries fail closed with `Optional.empty`.
- `NameDictionaryLoader` (`domain/namegen/`): strict YAML boundary — duplicate
  keys and unknown fields reject, future `schemaVersion` refuses to load, ≥4
  seed names required.
- `NameGenerationService` (`service/`): per-server catalog (never a static —
  cached in `StoryNpcs.serverNameServices`, evicted on server stop). Bundled
  dictionaries at `data/storynpcs/namegen/`; creator overrides at
  `config/storynpcs/namegen/` win on id collision (same contract as
  `SchematicStore`). Bad documents warn-and-skip.
- **14 bundled culture dictionaries**, covering every culture the target ships
  (roman, ancient_greek, japanese, old_norse, slavic, spanish, welsh, aztec,
  saami, customnpcs_classic) plus clean-room additions (saharan, celtic,
  french, fantasy). ~24 seed names each, hand-authored.
- Command surface: `/storynpcs npc set name <npc_id> random <culture>` —
  permission level 2 via the `set` subtree, culture argument suggests the live
  catalog, generation runs server-side with `level.getGameTime()` as the seed,
  applied through `mutateNpc` → canonical `replaceNpc` + entity refresh.
- Editor surface: 🎲 button beside the name field in `NpcEditorScreen`. Sends
  `randomizeNameCulture` on the existing `ServerboundNpcSavePayload` (`"*"` =
  server picks a culture deterministically from the sorted catalog under the
  same seed). The handler generates server-side, rejects malformed/unknown
  cultures with a structured `INVALID_PAYLOAD` save result, inserts the
  generated name, and continues through `replaceNpc` — the client can request
  randomization but never supplies the generated name. Success message echoes
  the generated name.
- `ServerboundNpcSavePayload` extended with `randomizeNameCulture` (bounded,
  null-safe, codec round-trip covered); the 4-arg convenience constructor is
  unchanged for all other save paths.

## Compat rows

All 34 markov inventory rows are `MAPPED_STORYNPCS_OBSERVED` via
`docs/parity/storynpcs-surface-map.json` row overrides:

- 15 `nikedemos.markovnames` classes → `NameDictionary`, `NameDictionaryLoader`,
  `MarkovNameGenerator`, `NameGenerationService` (+ per-culture YAML paths).
- 19 `data/customnpcs/markovnames/*.txt` files → the covering clean-room YAML
  dictionary (the target's per-gender txt splits map onto single
  culture dictionaries; the txt text is not reused).

## Evidence

- `NameGenerationServiceTest`: dictionary validation (order/id/name bounds),
  loader rejects (unknown fields, future schemaVersion, thin dictionaries),
  seeded determinism + seed variation, null/empty fail-closed, service
  suggest/cultures/unknown-culture surface, creator-dir load + bad-document
  skip, and **all 14 bundled dictionaries parse and generate**.
- `NetworkPayloadsTest`: `randomizeNameCulture` codec round-trip + empty-field
  default on the 4-arg form.
- `./gradlew test` — full suite green.
- `python tools/parity/compatibility_report.py` — validation PASS, 34 markov
  rows MAPPED (MAPPED 104→138).
- `python tools/parity/check_feature_status.py --check` — floor `tested` for
  the `markov-names` row (missing 5→4 overall).
- `python tools/parity/check_truth_gate.py` — pass.

## Explicit limits

- Generated names are *derived* from the seed dictionary, not looked up —
  output can be novel/unpronounceable; that is the nature of the feature.
- Seed is `level.getGameTime()` on live paths — deterministic per call, not
  reproducible across sessions (the generator itself is reproducible given a
  seed; callers wanting reproducibility pass a fixed seed — covered by tests).
- The 🎲 button does not live-refresh the name field on-screen; the generated
  name echoes in the save-result chat/system message and shows on reopen.
- Gendered name selection is a single mixed pool per culture (the target's
  per-gender txt files are represented but not split) — intentional format
  consolidation, not missing data.
- No live Minecraft/GameTest run was performed for this issue; coverage is
  unit-level for generator/loader/service plus codec and handler-level static
  wiring evidence.
