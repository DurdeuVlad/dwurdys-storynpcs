import hashlib
import json
import subprocess
from unittest import mock
import tempfile
import unittest
from pathlib import Path

from tools.parity import generate_target_surface_manifest as manifest_generator
from tools.parity.generate_target_surface_manifest import (
    EXPECTED_COUNTS,
    OPERATION_CLOSING_ISSUES,
    ROLE_ISSUES,
    SURFACE_PROVENANCE,
    SURFACE_COUNT_KEYS,
    SURFACE_ISSUES,
    TARGET_NAME,
    TARGET_SHA1,
    TARGET_SHA256,
    validate_manifest,
    verify_source_provenance,
    verify_all_research_target_hashes,
)


def valid_manifest() -> dict:
    surfaces = {}
    for surface, count_key in SURFACE_COUNT_KEYS.items():
        surfaces[surface] = []
        for index in range(1, EXPECTED_COUNTS[count_key] + 1):
            symbol = (
                list(ROLE_ISSUES)[index - 1]
                if surface == "roles"
                else f"{surface}-{index}"
            )
            provenance = SURFACE_PROVENANCE[surface]
            source = (
                symbol.replace(".", "/") + ".class"
                if provenance == "jar-class"
                else symbol
                if provenance == "jar-entry"
                else "inventories/parity_acceptance_matrix.json"
                if provenance == "research-inventory"
                else f"noppes/test/{surface}/{index}.java"
            )
            item = {
                "symbol": symbol,
                "domain": "test",
                "operation_family": "CROSS-CUTTING",
                "surface": surface,
                "inventory_id": f"target.{surface}.{index:04d}",
                "issue_ids": (
                    list(ROLE_ISSUES.values())[index - 1]
                    if surface == "roles"
                    else list(SURFACE_ISSUES[surface])
                ),
                "evidence_state": "VERIFIED_TARGET_SOURCE",
                "provenance": provenance,
                "source": source,
                "parity_fixture_ids": ["P0-4.test"],
                "coverage_status": "FIXTURE_REFERENCED",
            }
            if provenance == "research-inventory":
                item["source_sha256"] = "a" * 64
            if provenance == "decompiled-source":
                item["source_sha256"] = "a" * 64
                if surface == "persistence_stores":
                    item["source_files"] = [source]
                    item["source_sha256_by_file"] = {source: "a" * 64}
            surfaces[surface].append(item)
    for index, operation_id in enumerate(OPERATION_CLOSING_ISSUES, start=1):
        row = surfaces["operation_matrix"][index - 1]
        row["id"] = operation_id
        row["symbol"] = operation_id
        row["evidence_state"] = "INFERENCE_BACKED_BY_VERIFIED_TARGET_SOURCE"
        row["closing_issue_ids"] = OPERATION_CLOSING_ISSUES[operation_id]
    return {
        "manifest_version": 4,
        "artifact": {
            "name": TARGET_NAME,
            "sha256": TARGET_SHA256,
            "sha1": TARGET_SHA1,
            "evidence_state": "VERIFIED_TARGET_SOURCE",
            "archive_integrity": "PASS",
        },
        "issue_mapping": SURFACE_ISSUES,
        "counts": EXPECTED_COUNTS.copy(),
        "fixture_coverage": {
            "domains": {"test": ["P0-4.test"]},
            "operation_families": {
                operation: ["P0-4.test"] for operation in OPERATION_CLOSING_ISSUES
            },
        },
        "surfaces": surfaces,
    }


def issue_claims() -> dict[str, list[str]]:
    claims: dict[str, list[str]] = {}
    for surface, issue_ids in SURFACE_ISSUES.items():
        for issue_id in issue_ids:
            claims.setdefault(issue_id, []).append(surface)
    return claims


class TargetSurfaceManifestTest(unittest.TestCase):
    def test_expected_counts_are_explicit(self) -> None:
        self.assertEqual(EXPECTED_COUNTS["classes"], 1230)
        self.assertEqual(EXPECTED_COUNTS["assets"], 972)
        self.assertEqual(EXPECTED_COUNTS["data"], 49)
        self.assertEqual(EXPECTED_COUNTS["gui"], 149)
        self.assertEqual(EXPECTED_COUNTS["packets"], 155)
        self.assertEqual(EXPECTED_COUNTS["commands"], 70)
        self.assertEqual(EXPECTED_COUNTS["events"], 97)
        self.assertEqual(EXPECTED_COUNTS["persistence_stores"], 18)

    def test_each_surface_has_an_issue_owner(self) -> None:
        self.assertEqual(set(SURFACE_COUNT_KEYS), set(SURFACE_ISSUES))
        self.assertTrue(all(SURFACE_ISSUES.values()))

    def test_validator_rejects_missing_surface(self) -> None:
        manifest = valid_manifest()
        del manifest["surfaces"]["assets"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_validator_rejects_previous_manifest_schema(self) -> None:
        manifest = valid_manifest()
        manifest["manifest_version"] = 3
        with self.assertRaisesRegex(ValueError, "manifest_version must be 4"):
            validate_manifest(manifest)

    def test_validator_rejects_persistence_source_hash_map_drift(self) -> None:
        missing = valid_manifest()
        del missing["surfaces"]["persistence_stores"][0]["source_sha256_by_file"]
        with self.assertRaisesRegex(ValueError, "persistence source hash map missing"):
            validate_manifest(missing)

        extra = valid_manifest()
        store = extra["surfaces"]["persistence_stores"][0]
        store["source_sha256_by_file"]["noppes/extra/NotReferenced.java"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "persistence source hash map missing"):
            validate_manifest(extra)

        mismatched_primary = valid_manifest()
        store = mismatched_primary["surfaces"]["persistence_stores"][0]
        store["source_sha256_by_file"][store["source"]] = "b" * 64
        with self.assertRaisesRegex(ValueError, "persistence primary source hash drift"):
            validate_manifest(mismatched_primary)

    def test_validator_rejects_non_object_manifest(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be an object"):
            validate_manifest([])

    def test_validator_rejects_missing_or_drifted_artifact_identity(self) -> None:
        missing_artifact = valid_manifest()
        del missing_artifact["artifact"]
        with self.assertRaisesRegex(ValueError, "artifact identity"):
            validate_manifest(missing_artifact)

        altered_hash = valid_manifest()
        altered_hash["artifact"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "artifact identity"):
            validate_manifest(altered_hash)

    def test_validator_rejects_untrusted_record_types_and_provenance(self) -> None:
        malformed_symbol = valid_manifest()
        malformed_symbol["surfaces"]["target_classes"][0]["symbol"] = 7
        with self.assertRaisesRegex(ValueError, "symbol must be a non-empty string"):
            validate_manifest(malformed_symbol)

        forged_provenance = valid_manifest()
        forged_provenance["surfaces"]["target_classes"][0]["provenance"] = "forged"
        with self.assertRaisesRegex(ValueError, "provenance drift"):
            validate_manifest(forged_provenance)

    def test_validator_rejects_record_count_drift(self) -> None:
        manifest = valid_manifest()
        manifest["surfaces"]["commands"].pop()
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_validator_rejects_missing_required_field(self) -> None:
        manifest = valid_manifest()
        del manifest["surfaces"]["events"][0]["inventory_id"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_validator_rejects_missing_provenance(self) -> None:
        manifest = valid_manifest()
        del manifest["surfaces"]["events"][0]["provenance"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_validator_rejects_unknown_issue_mapping(self) -> None:
        manifest = valid_manifest()
        manifest["surfaces"]["target_classes"][0]["issue_ids"] = ["P999-999"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest, {"P0-1"}, issue_claims())

    def test_validator_rejects_missing_top_level_issue_mapping(self) -> None:
        manifest = valid_manifest()
        del manifest["issue_mapping"]

        with self.assertRaisesRegex(ValueError, "issue mapping drift"):
            validate_manifest(manifest)

    def test_validator_rejects_existing_but_wrong_issue_claim(self) -> None:
        manifest = valid_manifest()
        manifest["surfaces"]["gui"][0]["issue_ids"] = ["P0-1"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest, {"P0-1"}, {"P0-1": ["target_classes"]})

    def test_validator_rejects_issue_surface_claim_file_drift(self) -> None:
        claims = issue_claims()
        claims["P7-2"] = ["roles", "jobs"]
        known = {
            issue for values in SURFACE_ISSUES.values() for issue in values
        } | {issue for values in OPERATION_CLOSING_ISSUES.values() for issue in values}

        with self.assertRaisesRegex(ValueError, "issue surface claims drift"):
            validate_manifest(valid_manifest(), known, claims)

    def test_validator_rejects_duplicate_symbol(self) -> None:
        manifest = valid_manifest()
        manifest["surfaces"]["events"][1]["symbol"] = manifest["surfaces"]["events"][0]["symbol"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_validator_rejects_duplicate_or_mismatched_operation_ids(self) -> None:
        duplicate = valid_manifest()
        first = duplicate["surfaces"]["operation_matrix"][0]
        second = duplicate["surfaces"]["operation_matrix"][1]
        second["id"] = first["id"]
        second["symbol"] = first["symbol"]
        second["closing_issue_ids"] = first["closing_issue_ids"]
        with self.assertRaisesRegex(ValueError, "duplicate operation ID"):
            validate_manifest(duplicate)

        mismatch = valid_manifest()
        mismatch["surfaces"]["operation_matrix"][0]["symbol"] = "NOT-THE-OPERATION"
        with self.assertRaisesRegex(ValueError, "operation symbol must equal operation ID"):
            validate_manifest(mismatch)

    def test_source_verifier_rejects_class_without_target_jar_entry(self) -> None:
        manifest = {
            "surfaces": {
                "target_classes": [{
                    "symbol": "noppes.npcs.audit.NotInJar",
                    "source": "noppes/npcs/audit/NotInJar.class",
                    "provenance": "jar-class",
                }],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "JAR class missing"):
                verify_source_provenance(manifest, set(), root / "research", root / "decompiled")

    def test_source_verifier_rejects_decompiled_source_without_target_class(self) -> None:
        manifest = {
            "surfaces": {
                "gui": [{
                    "symbol": "Invented",
                    "source": "noppes/not-in-target/Invented.java",
                    "provenance": "decompiled-source",
                }],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "source missing from target JAR"):
                verify_source_provenance(manifest, set(), root / "research", root / "decompiled")

    def test_source_verifier_requires_matching_decompiled_source_hash(self) -> None:
        source = "noppes/npcs/gui/Example.java"
        class_entry = "noppes/npcs/gui/Example.class"
        content = b"package noppes.npcs.gui; class Example {}\n"
        manifest = {
            "surfaces": {
                "gui": [{
                    "symbol": "noppes.npcs.gui.Example",
                    "source": source,
                    "source_sha256": hashlib.sha256(content).hexdigest(),
                    "provenance": "decompiled-source",
                }],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "decompiled" / source
            source_path.parent.mkdir(parents=True)
            source_path.write_bytes(content)
            with (
                mock.patch.object(manifest_generator, "verify_pinned_research_inputs"),
                mock.patch.object(manifest_generator, "verify_source_record_bindings"),
            ):
                verify_source_provenance(manifest, {class_entry}, root / "research", root / "decompiled")

                source_path.write_bytes(content + b"// changed\n")
                with self.assertRaisesRegex(ValueError, "decompiled source hash drift"):
                    verify_source_provenance(manifest, {class_entry}, root / "research", root / "decompiled")

                del manifest["surfaces"]["gui"][0]["source_sha256"]
                with self.assertRaisesRegex(ValueError, "source hash missing or invalid"):
                    verify_source_provenance(manifest, {class_entry}, root / "research", root / "decompiled")

    def test_source_verifier_rejects_java_file_not_bound_to_manifest_symbol(self) -> None:
        source = "noppes/npcs/gui/OtherTab.java"
        inventory_source = "noppes/npcs/gui/ExpectedTab.java"
        content = b"package noppes.npcs.gui; class OtherTab {}\n"
        manifest = {
            "surfaces": {
                "gui": [{
                    "symbol": "ExpectedTab",
                    "class_name": "ExpectedTab",
                    "source": source,
                    "source_sha256": hashlib.sha256(content).hexdigest(),
                    "provenance": "decompiled-source",
                }],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "decompiled" / source
            source_path.parent.mkdir(parents=True)
            source_path.write_bytes(content)
            inventory_path = root / "research" / "inventories" / "all_gui_fields.json"
            inventory_path.parent.mkdir(parents=True)
            inventory_path.write_text(json.dumps({
                "target_sha256": TARGET_SHA256,
                "records": [{"class_name": "ExpectedTab", "source": inventory_source}],
            }), encoding="utf-8")

            with mock.patch.object(
                manifest_generator,
                "verify_pinned_research_inputs",
                return_value=None,
                create=True,
            ):
                with self.assertRaisesRegex(ValueError, "source mapping drift.*ExpectedTab"):
                    verify_source_provenance(
                        manifest,
                        {"noppes/npcs/gui/OtherTab.class"},
                        root / "research",
                        root / "decompiled",
                    )

    def test_source_verifier_hashes_every_persistence_store_source_file(self) -> None:
        controller = "noppes/npcs/controllers/StoreController.java"
        data_model = "noppes/npcs/controllers/data/StoreData.java"
        controller_content = b"package noppes.npcs.controllers; class StoreController {}\n"
        data_content = b"package noppes.npcs.controllers.data; class StoreData {}\n"
        hashes = {
            controller: hashlib.sha256(controller_content).hexdigest(),
            data_model: hashlib.sha256(data_content).hexdigest(),
        }
        manifest = {
            "surfaces": {
                "persistence_stores": [{
                    "symbol": "sample_store",
                    "owner": "StoreController",
                    "format": "JSON",
                    "source": controller,
                    "source_files": [controller, data_model],
                    "source_sha256": hashes[controller],
                    "source_sha256_by_file": hashes,
                    "provenance": "decompiled-source",
                }],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in ((controller, controller_content), (data_model, data_content)):
                source_path = root / "decompiled" / relative
                source_path.parent.mkdir(parents=True, exist_ok=True)
                source_path.write_bytes(content)
            inventory_path = root / "research" / "inventories" / "persistence_schema.json"
            inventory_path.parent.mkdir(parents=True)
            inventory_path.write_text(json.dumps({
                "target_sha256": TARGET_SHA256,
                "stores": [{
                    "id": "sample_store",
                    "owner": "StoreController",
                    "format": "JSON",
                    "source_files": [controller, data_model],
                }],
            }), encoding="utf-8")
            jar_entries = {
                controller.removesuffix(".java") + ".class",
                data_model.removesuffix(".java") + ".class",
            }

            with mock.patch.object(
                manifest_generator,
                "verify_pinned_research_inputs",
                return_value=None,
                create=True,
            ):
                verify_source_provenance(
                    manifest, jar_entries, root / "research", root / "decompiled"
                )
                (root / "decompiled" / data_model).write_bytes(data_content + b"// drift\n")
                with self.assertRaisesRegex(ValueError, "decompiled source hash drift.*StoreData"):
                    verify_source_provenance(
                        manifest, jar_entries, root / "research", root / "decompiled"
                    )
                (root / "decompiled" / data_model).write_bytes(data_content)
                with self.assertRaisesRegex(ValueError, "source missing from target JAR.*StoreData"):
                    verify_source_provenance(
                        manifest,
                        {controller.removesuffix(".java") + ".class"},
                        root / "research",
                        root / "decompiled",
                    )

    def test_source_record_binding_covers_every_decompiled_inventory(self) -> None:
        cases = (
            (
                "gui", "all_gui_fields.json", "records",
                {"class_name": "ExpectedTab", "source": "noppes/gui/ExpectedTab.java", "source_sha256": "a" * 64},
                {"symbol": "ExpectedTab", "class_name": "ExpectedTab", "source": "noppes/gui/ExpectedTab.java", "source_sha256": "a" * 64},
                "source",
            ),
            (
                "packets", "packets.json", "records",
                {"payload_id": "test:example", "message_class": "PacketExample", "direction": "client-to-server", "source": "noppes/PacketExample.java"},
                {"symbol": "PacketExample", "payload_id": "test:example", "message_class": "PacketExample", "direction": "client-to-server", "source": "noppes/PacketExample.java"},
                "source",
            ),
            (
                "commands", "command_tree.json", "leaves",
                {"path": ["noppes", "example"], "source": "noppes/CmdExample.java", "operation": "run example"},
                {"symbol": "noppes/example", "path": ["noppes", "example"], "source": "noppes/CmdExample.java", "operation": "run example"},
                "source",
            ),
            (
                "persistence_participants", "persistence.json", "records",
                {"class_name": "ExampleData", "source": "noppes/ExampleData.java", "role": "data-model"},
                {"symbol": "ExampleData", "class_name": "ExampleData", "source": "noppes/ExampleData.java", "role": "data-model"},
                "source",
            ),
            (
                "persistence_stores", "persistence_schema.json", "stores",
                {"id": "example_store", "owner": "ExampleController", "format": "JSON", "source_files": ["noppes/ExampleController.java", "noppes/ExampleData.java"]},
                {"symbol": "example_store", "owner": "ExampleController", "format": "JSON", "source": "noppes/ExampleController.java", "source_files": ["noppes/ExampleController.java", "noppes/ExampleData.java"]},
                "source_files",
            ),
        )
        for surface, filename, records_key, raw, row, mutated_field in cases:
            with self.subTest(surface=surface), tempfile.TemporaryDirectory() as directory:
                research_root = Path(directory)
                inventory_path = research_root / "inventories" / filename
                inventory_path.parent.mkdir(parents=True)
                inventory_path.write_text(
                    json.dumps({"target_sha256": TARGET_SHA256, records_key: [raw]}),
                    encoding="utf-8",
                )
                manifest = {"surfaces": {surface: [row]}}
                manifest_generator.verify_source_record_bindings(manifest, research_root)

                raw[mutated_field] = (
                    "noppes/Other.java"
                    if mutated_field == "source"
                    else ["noppes/Other.java"]
                )
                inventory_path.write_text(
                    json.dumps({"target_sha256": TARGET_SHA256, records_key: [raw]}),
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(ValueError, "source mapping drift"):
                    manifest_generator.verify_source_record_bindings(manifest, research_root)

    def test_pinned_research_revision_and_relevant_worktree_are_required(self) -> None:
        revision_result = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=manifest_generator.PINNED_RESEARCH_COMMIT + "\n", stderr=""
        )
        clean_result = subprocess.CompletedProcess(args=[], returncode=0, stdout="", stderr="")
        with mock.patch.object(
            manifest_generator.subprocess,
            "run",
            side_effect=[revision_result, clean_result],
        ) as run:
            manifest_generator.verify_pinned_research_inputs(Path("research"))
        self.assertEqual(run.call_count, 2)
        self.assertIn("--untracked-files=all", run.call_args_list[1].args[0])

        changed_revision = subprocess.CompletedProcess(args=[], returncode=0, stdout="0" * 40, stderr="")
        with mock.patch.object(manifest_generator.subprocess, "run", return_value=changed_revision):
            with self.assertRaisesRegex(ValueError, "research repository revision drift"):
                manifest_generator.verify_pinned_research_inputs(Path("research"))

        changed_inventory = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=" M inventories/packets.json\n", stderr=""
        )
        with mock.patch.object(
            manifest_generator.subprocess,
            "run",
            side_effect=[revision_result, changed_inventory],
        ):
            with self.assertRaisesRegex(ValueError, "local changes"):
                manifest_generator.verify_pinned_research_inputs(Path("research"))

    def test_validator_rejects_duplicate_inventory_id(self) -> None:
        manifest = valid_manifest()
        manifest["surfaces"]["events"][1]["inventory_id"] = manifest["surfaces"]["events"][0]["inventory_id"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_validator_rejects_wrong_role_issue_mapping(self) -> None:
        manifest = valid_manifest()
        bank = next(
            row for row in manifest["surfaces"]["roles"]
            if row["symbol"] == "noppes.npcs.roles.RoleBank"
        )
        bank["issue_ids"] = ["P6-2"]
        known = {
            issue for values in SURFACE_ISSUES.values() for issue in values
        } | {issue for values in OPERATION_CLOSING_ISSUES.values() for issue in values}

        with self.assertRaisesRegex(ValueError, "role issue mapping drift"):
            validate_manifest(manifest, known, issue_claims())

    def test_validator_rejects_unresolved_parity_fixture_reference(self) -> None:
        manifest = valid_manifest()
        manifest["fixture_coverage"]["domains"]["test"] = ["P0-4.missing"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_inventory_hash_scan_rejects_stale_nested_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            nested = Path(directory) / "nested"
            nested.mkdir()
            path = nested / "stale.json"
            path.write_text(
                '{"records": [{"target_sha256": "stale"}]}', encoding="utf-8"
            )
            with self.assertRaises(ValueError):
                verify_all_research_target_hashes(Path(directory))

    def test_inventory_hash_scan_accepts_target_hashes_in_lists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "classes.json"
            path.write_text(
                '{"target_sha256": "' + TARGET_SHA256 + '"}', encoding="utf-8"
            )
            verify_all_research_target_hashes(Path(directory))

    def test_validator_accepts_complete_manifest(self) -> None:
        known = {
            issue for values in SURFACE_ISSUES.values() for issue in values
        } | {issue for values in OPERATION_CLOSING_ISSUES.values() for issue in values}
        validate_manifest(valid_manifest(), known, issue_claims())


if __name__ == "__main__":
    unittest.main()
