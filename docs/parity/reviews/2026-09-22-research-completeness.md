# Independent review — research and issue completeness — 2026-09-22

Status: `FAIL` — read-only review; no source or documentation edits were made by the reviewer.

## Handoff

- **Lens:** Compare the exact decompiled CustomNPCs surface inventory and current issue register against the 15 operation families and 22 domains.
- **Evidence examined:** `docs/parity/target-surface-manifest.json`, `docs/parity/fixture-catalog.json`, `docs/CUSTOMNPCS_PARITY_TRACEABILITY.md`, `docs/CUSTOMNPCS_PARITY_ISSUE_REGISTER.md`, `tools/parity/fixture_harness.py`, and the exact SHA-256-matched JAR inventory.
- **Finding:** The fixture catalog referenced absent target symbols and dangling issue IDs; its original checks only required non-empty strings. The issue register omitted recipes/carpentry and registered content, linked NPCs/scenes/transformations/timers/natural spawning, custom GUI/HUD/overlay/model presets, administration/player/global data/configuration, optional integrations, and explicit UI onboarding/localization/high-DPI coverage.
- **Primary risks:** Static class-name coverage could be mistaken for executable parity; missing categories would make the final milestone incomplete even if every existing issue passed.
- **Fix applied:** Fixture validation now resolves exact manifest symbols and registered issue IDs; catalog mappings were corrected; the issue register now contains explicit P8-4/P8-5/P8-6/P9-4/P9-5 work items.
- **Verification:** `python -m unittest tools.parity.test_fixture_harness -v` — 7 tests passed; `python tools/parity/fixture_harness.py` — semantic validation passed.
- **Unresolved:** Target runtime behavior remains unprobed and therefore cannot be certified as parity.
