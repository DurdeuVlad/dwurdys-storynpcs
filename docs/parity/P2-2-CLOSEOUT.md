# P2-2 — Durable JSON store foundation closeout

Status: `DONE-LOCAL`

## Delivered locally

- Added `DurableJsonStore`, a shared mutable-state file policy with a version-1 envelope and legacy raw-JSON reads.
- Writes serialize to `<target>.tmp`, write all bytes through a `FileChannel`, call `force(true)`, rotate the prior three generations to `.bak.1` through `.bak.3`, atomically replace the target when supported, and best-effort fsync the parent directory after the rename so the directory entry itself survives power loss where the filesystem supports it.
- A filesystem that rejects `ATOMIC_MOVE` falls back to `REPLACE_EXISTING` and emits a one-time `[StoryNPCs]` stderr diagnostic per store directory rather than downgrading silently.
- A payload that would serialize to JSON `null` is rejected at write time so the store never produces a record its own reader refuses.
- Reads reject invalid or future schema versions, reject concatenated JSON values, quarantine invalid target/backup files, and recover the newest valid backup without deleting unrelated player records. When a corrupt target cannot be quarantined, a recovered backup is kept in memory and the invalid file is left in place as evidence rather than overwritten.
- Progression, bank vault, trade state, quest mail, and logical actor state repositories use the shared policy. Existing raw records remain readable and are normalized to the versioned envelope on the next save.
- Recovery diagnostics are retained in `ReadResult` and emitted by each repository with the exact affected record path.
- `FailurePoint` injection covers temporary write, force, backup rotation, target rename, and index-update stages so commit-boundary recovery can be tested deterministically without filesystem races.
- `PersistenceStoreMap` maps all 18 target persistence-store categories to StoryNPCs stores with declared ownership (WORLD/PLAYER/ENTITY/DEFINITION/CLIENT), maturity (IMPLEMENTED/COVERED/DEFERRED), concrete implementing classes for live stores, and the owning parity issue for deferred ones.
- `IndexedRecordStore` is the indexed world-scope record store: deterministic `record-<id>-<hash>.json` files (id embedded in the record payload) plus a durable `_index.json`, committed through `DurableJsonStore` with the `INDEX_UPDATE` failure point between record and index commits.
- A crash between record commit and index commit leaves a stale index that `open()` rebuilds from a directory scan; a corrupt index is quarantined and rebuilt; one invalid record file is skipped without erasing unrelated records; `read` never depends on the index.
- The index scan is deterministic and consistency-checked: a record whose embedded id does not match its deterministic file name is skipped with a diagnostic instead of being indexed under an unreachable id, and duplicate embedded ids resolve to the first file in sorted order with a diagnostic.
- `DurableOperationJournal` recovery scans tolerate bad records: a record that fails decode or field validation is quarantined (preserved as `.corrupted.*` evidence) and reported through `PendingScan.diagnostics()` instead of aborting the whole `pending()` scan; recovery call sites in `StoryNpcsApplicationService` print those diagnostics.
- Journal terminal-record pruning never deletes corrupt or unreadable record files, sweeps terminal records under a bounded retention policy, and removes aged orphan lock files.
- Multi-world isolation: all stores root under `worldDir/storynpcs/`, actor services and repositories are keyed per `MinecraftServer`, and `WorldScopeIdentity` persists a scope token that survives world relocation and fails closed on malformed identity.

## Evidence

- `DurableJsonStoreTest`: version envelope, legacy read/migrate-on-write, three-backup retention, corrupt-target quarantine/recovery, future-version refusal, concatenated-value refusal, null-envelope refusal, injected write/force/rotation/rename failures, and null-serializing-payload write rejection.
- `IndexedRecordStoreTest`: write/read/list round trip, injected crash between record and index commits recovering via rebuild, stale/corrupt index quarantine + rebuild with diagnostics, corrupt record skipped without erasing siblings, misplaced (embedded-id/file-name mismatch) record excluded from the index, delete-through-index-update, and open/validation guards.
- `DurableOperationJournalTest`: lifecycle/replay classification, identity binding, per-subject pending scans, retention pruning, and per-record invalid-record quarantine during `pending()` scans.
- `PersistenceStoreMapTest`: all 18 manifest symbols exactly once, ownership/status/store/owner declared per row, implemented rows name concrete store classes, deferred rows name their owning issue.
- Existing `ProgressionRepositoryTest`, `BankRepositoryTest`, `TradeStateRepositoryTest`, and `ActorLifecycleServiceTest`: repository reload, corruption evidence, journaled withdrawal stack, and actor-state restore remain green.
- Independent adversarial review: completed; findings remediated (invalid-record scan poisoning, stale/duplicated closeout evidence, silent non-atomic fallback, unquarantined-target overwrite, index scan consistency, null-payload write guard) or documented under Explicit limits.
- Focused persistence tests: `BUILD SUCCESSFUL`.
- Full suite: `BUILD SUCCESSFUL`, 1000 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.
- Research basis: NeoForge's SavedData model assigns durable data to an explicit world scope, requires dirty-state ownership, and uses a codec-backed `SavedDataType`; this slice applies the same explicit ownership/version boundary to file-backed player/economy/actor records. See [NeoForge Saved Data](https://docs.neoforged.net/docs/1.21.5/datastorage/saveddata/).

## Explicit limits

- Eight of the 18 target categories are still `DEFERRED` to their owning issues (P6-3 transports; P8 roles/jobs/companion/tool/world; P9-2; P9-4); the map declares their ownership and store intent now so those issues land on a fixed contract.
- `database` is `COVERED`: dedicated per-record JSON repositories intentionally replace the target's H2 relational store rather than reproducing it.
- Indexed-record filenames derive from id hash plus the embedded payload id — a theoretical hash collision is detected by the read-time id check, not silently merged.
- `DurableJsonStore` assumes one writer instance per target path: repositories construct stores per call and rely on repository-level monitors plus `synchronized` store methods; a second application service over the same directory is not a supported topology (the operation journal's `FileLock` enforces this at the journal layer and throws on cross-instance contention rather than interleaving).
- A legacy raw-JSON record that coincidentally contains a top-level `schemaVersion` field is treated as an envelope and will be quarantined if it fails envelope rules; no migration ambiguity is silently resolved.
- Orphaned `<target>.tmp`/`.recovery.tmp` files left by a hard crash self-clean on the next write to the same target; there is no open-time sweep.
- `TradeStateRepository`'s fail-closed latch is per-instance: once a durable record proves invalid, every access throws until the repository is reconstructed (world lifecycle reload).
- World-scoped NeoForge `SavedData` integration and operation-level exactly-once transaction coverage beyond the bank/trade journals remain P2-3 work.
