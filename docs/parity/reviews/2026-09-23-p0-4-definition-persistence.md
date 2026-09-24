# P0-4 definition persistence adversarial review

Date: 2026-09-23  
Auditor: Halley (read-only; distinct from implementer)  
Final verdict: `PASS` for the reviewed YAML definition save/delete consistency slice. This is not approval of target-runtime parity or the remaining P0-4 fixtures.

## Review findings and disposition

- Stale source index after deletion: fixed. Successful delete clears the `(type, NamespacedId) -> source paths` entry; application-service saves record the final path after writing. Missing indexed paths fail closed and require a definitions reload.
- Save/delete registry and disk race: fixed for application-service mutations. Save and delete operations serialize on the service instance, and the service writer plus loader deletion share the loader-owned `DefinitionWriteCoordinator` for in-process serialization and the definition-directory file lock for cooperating processes.
- `FileLock` is not an in-process thread mutex: addressed. Java SE 21 says JVM file locks are not suitable for coordinating same-JVM threads and that some systems may release other JVM locks when a channel closes. The application-owned coordinator supplies the local mutex; file locks remain the cooperating-process boundary. Standalone concurrent writer instances must be constructed with a shared coordinator.
- Delete failure could remove live state while leaving YAML: fixed. The service deletes the persisted source before removing the registry entry or advancing its revision. Stale indexed sources, unsafe paths, and I/O errors reject the operation; canonical adapters return `DEFINITION_DELETE_FAILED` while the definition remains registered.
- Partial deletion of duplicate-ID files: fixed by refusal. The loader verifies candidate sources, then rejects more than one verified source before deleting any file. An operator must resolve duplicate files and reload before retrying. A regression asserts both files and both index entries survive refusal.
- Hostile local path swap: residual, explicitly accepted for this slice. Java path validation plus atomic rename does not make validation and path use indivisible against an untrusted local filesystem actor. Definitions root and parents must remain administrator-controlled.

## Evidence

- Focused command: `./gradlew.bat test --tests com.storynpcs.service.StoryNpcsApplicationServiceTest --tests com.storynpcs.yaml.YamlDefinitionWriterTest --tests com.storynpcs.yaml.YamlDefinitionLoaderTest --console=plain` — passed on the final code revision.
- Full evidence command: `python tools/parity/run_storynpcs_fixtures.py` — Gradle passed with 337 test cases across 47 suites; adapter status `PASS`; 59 exact mapped selectors yielded partial observations for 15 fixtures; 9 fixtures remain unmapped and blocked; 0 mapped cases failed or skipped. A separate symlink test was skipped because this Windows account could not create a symbolic link.
- `python -m unittest discover -s tools/parity -p "test_*.py"` — 65 tests passed.
- `python tools/parity/check_truth_gate.py` and `python tools/parity/fixture_harness.py` — both passed; the harness continues to report target parity `BLOCKED` because all target-runtime evidence is unverified.

## Files and symbols reviewed

- `src/main/java/com/storynpcs/yaml/DefinitionWriteCoordinator.java` — lifecycle-owned per-directory local mutexes.
- `src/main/java/com/storynpcs/yaml/DefinitionWriteLock.java` — cooperating-process lock token and cleanup.
- `src/main/java/com/storynpcs/yaml/YamlDefinitionWriter.java` — safe filename identity, source resolution, atomic temporary-file replacement.
- `src/main/java/com/storynpcs/yaml/YamlDefinitionLoader.java` — indexed-source refresh and fail-closed delete.
- `src/main/java/com/storynpcs/service/StoryNpcsApplicationService.java` — shared coordinator wiring, persistence-first saves/deletes, canonical rejection.
- `src/test/java/com/storynpcs/service/StoryNpcsApplicationServiceTest.java` and `src/test/java/com/storynpcs/yaml/YamlDefinitionWriterTest.java` — source-index, failure, duplicate, and concurrency regressions.
- `docs/parity/fixture-catalog.json` — exact selectors and observed/uncovered scope.

## Primary technical reference

Java SE 21 documents the same-JVM and advisory-lock limitations in [`FileLock`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html). Its [`Files.move` contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html) defines `ATOMIC_MOVE` as an atomic filesystem operation when supported and permits `AtomicMoveNotSupportedException` otherwise. The implementation fails rather than silently falling back to a non-atomic move.
