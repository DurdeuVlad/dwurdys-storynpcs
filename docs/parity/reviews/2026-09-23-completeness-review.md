# Independent parity-plan completeness review — 2026-09-23

## Verdict

**FAIL at review time.** The plan’s architecture and product direction matched the repository contract, but the generated inventory/fixture links, execution requirements, ownership, and sequence were not reliable enough for an implementation-ready handoff. The reviewer made no changes and read only the seven authorized source documents/manifests.

## Findings from the independent auditor

1. The manifest assigned synthetic `target.*` IDs as `fixture_id` values, while the fixture catalog used runnable `P0-4.*` IDs. **0 of 2,836** manifest rows resolved to a catalog fixture, despite P0-1 requiring fixture references.
2. All **24** catalog fixtures included `target-runtime` in `required_layers`; this incorrectly prevented StoryNPCs-only execution while the exact target runtime was unavailable. The target evidence state itself remained honest: no manifest row claimed target-runtime or parity evidence.
3. Ownership was wrong or too broad: the Roles domain and all target roles were assigned to P6-2, trader/bank roles were not assigned to P7-1/P7-2, Jobs traceability assigned implementation to P6-5 instead of P6-4, and all 15 operation rows listed only P0-4 rather than their closing implementation issues.
4. The prescribed sequence put P1-2 before its blocking prerequisite P2-2.
5. The roadmap called its drafts superseded but still described them as independently handoff-ready; its old P0 issue titles conflicted with the canonical register.
6. P9-2 scripting quotas and UI minimum resolution lacked pass/fail values.
7. The catalog had nine fixtures with empty JUnit selector lists, including combat, inventory, jobs, transport, companions, spawners, marks, creator tools, and scripting. Those were uncovered evidence, not passes.

## Independent checks recorded by the auditor

- 47 unique issue IDs matched the milestone map; required handoff sections were present.
- All 15 operation families and 22 domains appeared in the catalog.
- The expanded dependency graph had 272 edges, no dangling IDs, and no cycles; it exposed the P2-2 → P1-2 prerequisite inversion in the prose flow.
- Static target-source states did not claim runtime behavior or parity.
- Read-only JSON/PowerShell checks and `rg -n` were used. The auditor did not run Gradle because this was a documentation review.

## Corrective work

- The manifest generator now calls each generated `target.*` value an `inventory_id`; it adds `parity_fixture_ids` and `coverage_status`, with a validated domain/operation crosswalk. Operation rows record their closing issues. The checked-in manifest now has 2,836 inventory rows: **1,606 rows link to resolvable catalog fixtures**, and **1,230 class inventory rows are explicitly inventory-only** rather than synthetic tests. All 22 domains, 15 operation families, and 24 fixture IDs resolve.
- Fixture-catalog schema v2 separates StoryNPCs `required_layers` from `target_probe_policy`; no fixture requires target-runtime merely to execute. Target behavior remains `UNVERIFIED_TARGET_RUNTIME` until observed in the exact JAR runtime.
- Role manifest mappings and traceability now assign trader to P7-1, bank to P7-2, transporter to P6-3, social/service roles to P6-2, and companion/follower to P6-5. Jobs are assigned to P6-4, with companion job variants assigned to P6-5.
- The flow now orders P2-1/P2-2 before P1-2. The roadmap explicitly labels old issue drafts historical, superseded, and non-actionable.
- P9-2 now has explicit initial instruction, wall-time, memory, recursion, canonical-operation, and per-tick scheduler budgets. UI acceptance now defines an 854x480 physical display at GUI scale 2 (minimum 427x240 logical viewport).
- The parity runner now fails if any fixture lacks a JUnit selector or is otherwise blocked. The nine existing empty selector mappings remain open implementation work; structural catalog validation reports `storynpcs_execution_coverage: INCOMPLETE` rather than implying those tests exist.
- A separate source check confirmed `StoryNpcsCommands` constructs and persists quickstart NPC/dialogue definitions in Java. P2-1 now requires bundled, versioned namespaced YAML resources loaded read-only and a quickstart path that resolves/spawns them without saving definition objects.

## Verification status

The auditor’s verdict applies to the pre-correction snapshot. After the initial 42 focused tests, the main agent added schema-version, malformed/dangling-reference, runtime-layer, role-owner, operation-owner, and unmapped-selector regressions. Final verification: `python -m unittest discover -s tools/parity -p "test_*.py"` — **73 passed**; `python tools/parity/check_truth_gate.py` — passed; `.\gradlew.bat test --rerun-tasks --console=plain` — `BUILD SUCCESSFUL`, 47 suites/337 tests/0 failures/0 errors/1 skipped; manifest/catalog structural report — PASS with 24 certification blockers and 9 unmapped StoryNPCs JUnit fixtures. Full source regeneration from the exact JAR/Vineflower source root remains open in P0-1. No target-runtime or full product-parity claim is made.
