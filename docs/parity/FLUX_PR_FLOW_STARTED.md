# Flux PR flow — local start

Status: `ACTIVE-LOCAL` — started 2026-09-22 after the required three-review/three-fix gate.

## Authority boundary

- The user authorized filling the parity potholes and implementing the registered issues. GitHub issue-tracker changes for the parity plan are also authorized and have been recorded in milestone #9 and issues #47/#48; existing issues #44/#45 were updated to link their bounded slices to the canonical plan.
- No branch push, pull request creation, or merge authority was supplied; this flow operates in the shared worktree and records evidence locally.
- Each issue is processed serially: implement, test, verify, adversarially challenge, record residual risks, then advance.
- Final merge-readiness requires the repository adversarial review skill and must not be inferred from passing tests.

## First flow step

`P0-1` and `P0-4` are `DONE-LOCAL` as static source-inventory and test-infrastructure deliverables. The v4 manifest is bound to the exact target JAR and pinned research inputs; the fixture system now has 25 fixtures across 15 operations/22 domains and 86 mapped selectors across 20 observed fixtures. Five feature-owned fixtures remain unmapped, partial behavior scopes remain explicit, and all target outcomes remain `UNVERIFIED_TARGET_RUNTIME`; the strict fixture/parity predicate is still blocked. The separate tracker issues remain open. P1-1 is the active local implementation slice and remains `IN-REVIEW`.

## Evidence for the start gate

- Three independent failed reviews and three corrective fixes: `docs/parity/reviews/2026-09-22-*.md`.
- Historical start-gate Java suite: 322 tests, 0 failures/errors. It is not the latest complete test campaign.
- Latest parity-tool suite: 108 tests passed; truth gate passed. The exact-source fixture path reports `source_provenance_status=VERIFIED`; the latest combined CI-mode run passed test-execution validation with 385 cases/51 suites, 86 mapped selectors, 20 observed fixtures, 5 declared blockers, 0 mapped failures, and target parity still `BLOCKED`.
- P0 exact-source manifest v4 SHA-256: `7a6e3337e19be0dd3c9ac6fd356c563ae9d9ff82ca82518f35a6f076ab27769c`; independent provenance audit returned `SUPPORTED` for the recorded source/identity checks (the expanded current crosswalk has since been regenerated and retested).

## Next serial step

The bounded P1-1 payload/retry boundary was approved by independent read-only review; the quest subpath now includes typed player-scoped start/progress, persisted revision advancement through completion, bounded objective thresholds, commit-ordered reentrancy-safe event groups, and per-player in-process turn-in serialization. Latest full Java verification reports 385 cases; 86 selectors map across 20 observed fixtures and 5 remain declared blockers, while target parity remains blocked. Keep P1-1 `IN-REVIEW` until reward completion envelopes, faction progression, and the non-envelope follower/bank/template/tool paths are complete. Continue serially through the issue register. P2-3 remains `IN-REVIEW`: XP/item rewards can still duplicate after a failed progression save; crash-safe reward fan-out and durable markers for trade output/payment, paid unlock, and remaining bank actions are still absent.
