# P2-2 — Durable JSON store foundation closeout

Status: `IN-REVIEW`

## Delivered locally

- Added `DurableJsonStore`, a shared mutable-state file policy with a version-1 envelope and legacy raw-JSON reads.
- Writes serialize to `<target>.tmp`, write all bytes through a `FileChannel`, call `force(true)`, rotate the prior three generations to `.bak.1` through `.bak.3`, and atomically replace the target when supported.
- Reads reject invalid or future schema versions, reject concatenated JSON values, quarantine invalid target/backup files, and recover the newest valid backup without deleting unrelated player records.
- Progression, bank vault, and logical actor state repositories now use the shared policy. Existing raw records remain readable and are normalized to the versioned envelope on the next save.
- Recovery diagnostics are retained in `ReadResult` and emitted by each repository with the exact affected record path.
- `FailurePoint` injection covers temporary write, force, backup rotation, and target rename stages so commit-boundary recovery can be tested deterministically without filesystem races.

## Evidence

- `DurableJsonStoreTest`: version envelope, legacy read/migrate-on-write, three-backup retention, corrupt-target quarantine/recovery, future-version refusal, concatenated-value refusal, null-envelope refusal, and injected write/force/rotation/rename failures.
- Existing `ProgressionRepositoryTest`, `BankRepositoryTest`, and `ActorLifecycleServiceTest`: repository reload, corruption evidence, automatic bank persistence, and actor-state restore remain green.
- Focused persistence tests: `BUILD SUCCESSFUL`.
- Full suite: `BUILD SUCCESSFUL`, 284 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.
- Research basis: NeoForge's SavedData model assigns durable data to an explicit world scope, requires dirty-state ownership, and uses a codec-backed `SavedDataType`; this slice applies the same explicit ownership/version boundary to file-backed player/economy/actor records. See [NeoForge Saved Data](https://docs.neoforged.net/docs/1.21.5/datastorage/saveddata/).

## Explicit limits

- The complete target inventory of 18 persistence categories is not yet mapped to concrete StoryNPCs stores.
- No index-update stage exists yet because the current stores are independent record files; an indexed world store will need an additional commit stage and fixture.
- World-scoped NeoForge `SavedData` integration, backup retention policy configuration, and operation-level exactly-once transaction journals remain P2-3 work.
- Independent adversarial review is still required before this high-risk persistence issue can move to `DONE-LOCAL`.
