# Independent review — architecture and milestone completeness — 2026-09-22

Status: `FAIL` — read-only review; no source or documentation edits were made by the reviewer.

## Handoff

- **Lens:** Check the YAML-first, canonical-service, graph-dialogue, managed-lifecycle, and durable-persistence directives against production code and milestone claims.
- **Evidence examined:** `src/main/java/com/storynpcs/StoryNpcs.java`, `StoryNpcsCommands.java`, `StoryNpcsNetwork.java`, `StoryNpcsApplicationService.java`, the P1/P2 closeouts, and the target operation/domain register.
- **Finding:** A quickstart command still hardcodes a complete NPC/dialogue definition; most progression/faction/follower/trade/bank paths lack a complete typed request boundary; withdrawal delivery is still packet-side; mutable `StoryNpcs.instance` remains; definitions and runtime trade use state are not completely separated; P0-4 structural probes were not executable; P2-3 still had withdrawal, paid-unlock, quest fan-out, and trade crash gaps; M4 lacked spatial indexing, interest management, backpressure, and fairness controls.
- **Primary risks:** The project could claim architectural compliance while adapters still own mutation or while entity/runtime state is shared globally.
- **Fix applied:** Stable trade listing identities are now deterministic/persisted and used by durable usage state and operation subjects; duplicate trade/deposit requests now reach the canonical journal path and client screens reuse request IDs.
- **Verification:** Focused Gradle tests for trade state/serialization/session/network behavior passed with `BUILD SUCCESSFUL`.
- **Unresolved:** The mutable static lifecycle issue, full typed operation migration, and M4 scale controls remain open and must not be marked complete.
