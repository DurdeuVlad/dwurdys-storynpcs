# P0-1 source-provenance adversarial review

Status: `SUPPORTED` — independent read-only review of the v4 source-record binding and persistence-source checks found neither original P0-1 bypass. The auditor made no workspace edits.

## Scope reviewed

- `tools/parity/generate_target_surface_manifest.py`: `persistence_store_records`, `verify_pinned_research_inputs`, `source_record_key`, `verify_source_record_bindings`, `verify_source_provenance`, and `validate_manifest`.
- `tools/parity/test_target_surface_manifest.py`: wrong-symbol source mapping, all five source-inventory binding types, repository pinning, secondary persistence-source hash and class-membership regressions.
- `docs/parity/target-surface-manifest.json`: generated v4 record schema and per-file persistence hash coverage.

## Auditor handoff

- **Outcome:** `SUPPORTED` for the reviewed issues. Manifest and authoritative inventory identity sets must match; each source path and applicable identity fields are compared on the matching row. Every persistence `source_files` path has a digest, must map to a target-JAR `.class` entry, resolve under the configured decompiled root, exist, and match its digest. The source inventory must come from commit `9f7a921a5c3fbc66199b0d7719bdc0c56081745d` with relevant inputs clean.
- **Evidence:** the regression suite rejects an otherwise valid target-JAR source attached to the wrong GUI identity, altered secondary persistence contents, absent secondary class membership, malformed per-file digest maps, a wrong research revision, and modified inventory inputs.
- **Commands and results:** `python -m unittest tools.parity.test_target_surface_manifest -v` — 30 passed; `python -m unittest discover -s tools/parity -p "test_*.py"` — 108 passed; `python tools/parity/check_truth_gate.py` — passed; `./gradlew.bat cleanTest test --rerun-tasks --console=plain` — 47 suites/339 cases, 0 failures/errors, 1 skipped. Exact-source `run_catalog` returned `validation_status=PASS`, `source_provenance_status=VERIFIED`, and `parity_status=BLOCKED`. The fresh exact-source combined CI run exited 0 with 19 mapped fixtures observed, 5 declared feature blockers, and 0 failed tests; target parity remained `BLOCKED`.
- **Generated evidence:** manifest v4 SHA-256 is `82975f13854368b657a891e08ed4274094619d9c9995480c87f2fba5b2ba514c`; two independent generations were byte-identical. Eighteen persistence-store records contain 82 source-file references and 82 hashes for 81 unique Java files.
- **Assumptions:** the pinned research inventories are the reviewed authority for class/payload/command/store identity and source-path association. The exact JAR digest is fixed in the generator and rechecked before generation.
- **Residual risks:** source inventory and hashes are static evidence, not target-runtime behavior or semantic parity. Five feature fixtures remain unmapped, four mapped scopes remain partial, and all 24 target-runtime observations are unverified. Exact manifest regeneration requires the external target JAR, pinned research repository, and Vineflower output; those inputs are not included in a clean primary-repository checkout. GitHub issues #47 and #48 remain open; no tracker state was changed by this review.
- **Blockers:** none for local completion of the P0-1 static manifest slice. Feature parity and target-runtime certification remain open.

## How other provenance systems handle this

This implementation follows the small, relevant parts of established provenance practice: SLSA records resolved source dependencies at a specific revision/digest, while in-toto binds named subjects to cryptographic digests. We use a pinned commit, explicit record identity, source paths, and SHA-256 checks without introducing a separate signing or attestation service. See [SLSA build provenance](https://slsa.dev/spec/v1.2/build-provenance) and the [in-toto Statement v1 specification](https://github.com/in-toto/attestation/blob/main/spec/v1/statement.md).
