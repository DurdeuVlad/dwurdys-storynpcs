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

- `PersistenceStoreMap` now maps all 18 target persistence-store categories to StoryNPCs stores with declared ownership (WORLD/PLAYER/ENTITY/DEFINITION/CLIENT), maturity (IMPLEMENTED/COVERED/DEFERRED), concrete implementing classes for live stores, and the owning parity issue for deferred ones.
- `IndexedRecordStore` is the indexed world-scope record store: deterministic `record-<id>-<hash>.json` files (id embedded in the record payload) plus a durable `_index.json`, committed through `DurableJsonStore` with the new `INDEX_UPDATE` failure point between record and index commits.
- A crash between record commit and index commit leaves a stale index that `open()` rebuilds from a directory scan; a corrupt index is quarantined and rebuilt; one invalid record file is skipped without erasing unrelated records; `read` never depends on the index.

## Evidence

- `DurableJsonStoreTest`: version envelope, legacy read/migrate-on-write, three-backup retention, corrupt-target quarantine/recovery, future-version refusal, concatenated-value refusal, null-envelope refusal, and injected write/force/rotation/rename failures.
- `IndexedRecordStoreTest`: write/read/list round trip, injected crash between record and index commits recovering via rebuild, stale/corrupt index quarantine + rebuild with diagnostics, corrupt record skipped without erasing siblings, delete-through-index-update, and open/validation guards.
- `PersistenceStoreMapTest`: all 18 manifest symbols exactly once, ownership/status/store/owner declared per row, implemented rows name concrete store classes, deferred rows name their owning issue.
- Existing `ProgressionRepositoryTest`, `BankRepositoryTest`, `DurableOperationJournalTest`, and `ActorLifecycleServiceTest`: repository reload, corruption evidence, automatic bank persistence, journal lifecycle, and actor-state restore remain green (failure-point iterations now scoped to record-commit stages with `INDEX_UPDATE` documented as store-level).
- Focused persistence tests: `BUILD SUCCESSFUL`.
- Full suite: `BUILD SUCCESSFUL`, 480 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.
- Research basis: NeoForge's SavedData model assigns durable data to an explicit world scope, requires dirty-state ownership, and uses a codec-backed `SavedDataType`; this slice applies the same explicit ownership/version boundary to file-backed player/economy/actor records. See [NeoForge Saved Data](https://docs.neoforged.net/docs/1.21.5/datastorage/saveddata/).

## Explicit limits

- Eight of the 18 target categories are still `DEFERRED` to their owning issues (P6-3, P8-1..P8-6, P9-2, P9-4); the map declares their ownership and store intent now so those issues land on a fixed contract.
- `database` is `COVERED`: dedicated per-record JSON repositories intentionally replace the target's H2 relational store rather than reproducing it.
- Indexed-record filenames derive from id hash plus the embedded payload id — a theoretical hash collision is detected by the read-time id check, not silently merged.
- World-scoped NeoForge `SavedData` integration, backup retention policy configuration, and operation-level exactly-once transaction journals remain P2-3 work.
- Independent adversarial review is still required before this high-risk persistence issue can move to `DONE-LOCAL`.
