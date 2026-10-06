# Final adversarial review — addendum 2026-10-06

Scope: everything delivered after the [2026-09-25 final review](2026-09-25-final-adversarial.md)
— the M9 residual batch (#84/#85/#86/#122/#118), M10 authoring/docs (#67, #88–#90),
M12 player screens (#150), and M11 import/evidence (#91, #92). Method unchanged:
hunt for places where the implementation could lie — silently, partially, or by
overstating evidence.

## High-risk checks performed (this tranche)

| Check | Result |
|---|---|
| Script sandbox escape | `ScriptHost` ClassShutter admits only `com.storynpcs.script.api.*`; Java bridges stripped, E4X off; `ScriptBudget` meters instructions/memory/recursion/canonical ops; 3-strike quarantine. `P92ScriptHostTest` (24 fixtures). Disclosed residual: single-step JS heap growth is not hard-bounded — VM exhaustion converts to quarantine rather than crashing. |
| Patch-plan apply bypassing canonical boundary | `PatchPlanApplier` dispatches only through `StoryNpcsApplicationService` ops with per-definition expected-revision, capability, fresh request IDs, proven permission level; apply scope limited to npc/dialogue/quest/faction — other families reject `PATCH_APPLY_SCOPE`; failed apply restores serde snapshots (`RESTORED`/`ROLLBACK_INCOMPLETE`). 8 fixtures incl. stale-base no-write and absent-delete no-op. |
| Player-panel replay/identity trust | Panel mutations carry session + request IDs; server rebuilds views (never trusts client payloads); `admitRequest` result is *consumed* — a replayed craft/hire commit cannot double-spend ingredients or wages (caught in review: the admission result was initially ignored). Stale `closePanel` cannot invalidate a newer screen — the close path compares the presented session token. |
| Import pretending to support unevidenced formats | `TARGET_WORLD_NBT`/`CUSTOMNPCS_TEXT_EXPORT` remain `UNSUPPORTED_NEEDS_EVIDENCE`; unknown fields quarantine whole documents; referenced scripts/assets surface as `res[...]` rows *including* quarantined documents. |
| Evidence inflation | `check_feature_status.py` derives floors from files/wired/registered/tests/absent; `declared` claims get a 2-hop cross-package isolation scan. The P11-2 pass demoted 10 stale `declared` rows and fixed 3 stale `absent` rows — the checker caught real overclaims rather than being tuned to pass. `absent_exempt` exists only so parity *catalogs* may name target classes; it does not weaken the scan elsewhere (tested). |
| Fixture-evidence staleness | `storynpcs-fixture-report.json` is fingerprint-bound to `src/`, `tools/parity/*.py`, `docs/creator`, and the evidence manifests; a stale `P7DomainTest` selector (parameterized name drift) was caught and corrected — the fingerprint mechanism proved it detects drift. |
| Benchmark honesty | Live-neoforge-gametest artifacts report `LIVE_RUNTIME_FAIL` for population/stress p95/p99 thresholds and `LIVE_RUNTIME_PASS` only for siege — the failures are recorded, not tuned away. The truthful line is hash-pinned in the truth gate; unreviewed scale claims still fail. |
| Register/tracker truth | 13 `IN-REVIEW`/`IN-PROGRESS` register rows whose tracker issues were closed+merged corrected to `DONE` with closure refs; 3 off-vocabulary `IMPLEMENTED` statuses normalized. The release gate now reports every issue terminal except P11-3 itself. |

## Findings (new, this pass)

1. **Panel-action replay (fixed before merge).** `handlePanelAction` consulted
   `RuntimeSessionRegistry.admitRequest` but ignored the `RequestAdmission`
   result — a replayed carpentry/hire commit would have double-charged
   resources. Now gated on `NEW`; duplicates fail closed.
2. **Late-close session invalidation (fixed before merge).** Custom-GUI close
   previously cleared the player's panel session unconditionally; a slow close
   packet could invalidate a newer screen's session. Now compares tokens.
3. **Evidence-tooling drift (fixed this issue).** Feature-status `declared`
   claims, stale `absent` patterns, stale generated doc section, and a stale
   fixture selector all regressed silently as code shipped — now re-derived
   from disk and pinned by tests.

## Residual risks (honest, accepted)

- Unchanged from the 2026-09-25 review: **no live target-runtime probe**, so
  every behavioral manifest row remains `UNVERIFIED_TARGET_RUNTIME` and the
  release gate stays `BLOCKED` by construction. Headless + GameTest evidence
  does not certify target parity.
- Live-screen rendering is verified at layout/model level only; no rendered
  client screenshots exist for the M10–M12 screens.
- Importer rollback assumes the JVM survives a mid-apply crash (per-definition
  atomicity, not per-package).

## Verdict

`ADOPT` — same posture as the prior review: ship `IN-REVIEW` /
`HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED`; no evidence supports a stronger label.
The P11-2 evidence closure keeps that verdict honest rather than inflating it.
