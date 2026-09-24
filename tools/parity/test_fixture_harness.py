import copy
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from tools.parity.fixture_harness import (
    DOMAINS,
    OPERATION_FAMILIES,
    expand_fixtures,
    load_catalog,
    run_catalog,
    validate_catalog,
    validate_manifest_fixture_coverage,
)
from tools.parity.evidence_gate import evaluate_fixtures
from tools.parity.generate_target_surface_manifest import TARGET_NAME, TARGET_SHA1, TARGET_SHA256
import tools.parity.fixture_harness as fixture_harness


def run_catalog_with_manifest(root: Path, catalog: dict, manifest: dict) -> dict:
    with tempfile.TemporaryDirectory() as directory:
        temporary_root = Path(directory)
        parity_dir = temporary_root / "docs" / "parity"
        parity_dir.mkdir(parents=True)
        catalog_path = parity_dir / "fixture-catalog.json"
        catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
        (parity_dir / "target-surface-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        (parity_dir / "issue-surface-claims.json").write_text(
            (root / "docs" / "parity" / "issue-surface-claims.json").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        (temporary_root / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md").write_text(
            (root / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        return run_catalog(catalog_path)


class FixtureHarnessTest(unittest.TestCase):
    def test_checked_in_catalog_covers_all_operations_and_domains(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        manifest = json.loads((root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8"))
        target_symbols = {
            row["symbol"] for row in manifest["surfaces"]["target_classes"]
        }
        issue_ids = {
            line.split()[1]
            for line in (root / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md").read_text(encoding="utf-8").splitlines()
            if line.startswith("### P")
        }
        self.assertEqual(
            validate_catalog(catalog, target_symbols=target_symbols, registered_issue_ids=issue_ids), []
        )
        self.assertEqual(set(catalog["required_operation_families"]), OPERATION_FAMILIES)
        self.assertEqual(set(catalog["required_domains"]), DOMAINS)
        self.assertTrue(all("target-runtime" not in fixture["required_layers"] for fixture in catalog["fixtures"]))
        self.assertTrue(all(fixture["target_probe_policy"] == "optional" for fixture in catalog["fixtures"]))
        self.assertEqual(
            {fixture_id: blocker["owner_issue_ids"]
             for fixture_id, blocker in catalog["storynpcs_test_blockers"].items()},
            {
                "P0-4.jobs": ["P6-4"],
                "P0-4.transport": ["P6-3"],
                "P0-4.spawner": ["P8-1"],
                "P0-4.creator-tools": ["P8-2", "P8-3"],
                "P0-4.scripting": ["P9-1", "P9-2"],
            },
        )

    def test_catalog_expands_to_blocked_unverified_evidence(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        report = run_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        self.assertEqual(report["validation_status"], "PASS")
        self.assertEqual(report["status"], "BLOCKED")
        self.assertEqual(report["source_provenance_status"], "UNVERIFIED")
        self.assertEqual(report["coverage"]["fixture_count"], 25)
        self.assertEqual(report["coverage"]["mapped_junit_fixture_count"], 20)
        self.assertEqual(len(report["coverage"]["unmapped_junit_fixture_ids"]), 5)
        reported_blockers = {
            entry["fixture_id"]: entry["owner_issue_ids"]
            for entry in report["coverage"]["unmapped_junit_fixture_blockers"]
        }
        self.assertEqual(set(reported_blockers), set(catalog["storynpcs_test_blockers"]))
        self.assertTrue(all(
            reported_blockers[fixture_id] == blocker["owner_issue_ids"]
            for fixture_id, blocker in catalog["storynpcs_test_blockers"].items()
        ))
        self.assertEqual(report["storynpcs_execution_coverage"], "INCOMPLETE")
        self.assertEqual(report["evidence"]["parity_status"], "BLOCKED")
        self.assertFalse(report["evidence"]["certification_eligible"])
        self.assertEqual(len(expand_fixtures(catalog)), 25)

    def test_run_catalog_only_passes_after_exact_jar_and_source_provenance_check(self):
        root = Path(__file__).resolve().parents[2]
        catalog_path = root / "docs" / "parity" / "fixture-catalog.json"
        with (
            mock.patch.object(
                fixture_harness,
                "jar_inventory",
                return_value=([], TARGET_SHA256, TARGET_SHA1),
            ),
            mock.patch.object(fixture_harness, "verify_source_provenance") as verify_source,
        ):
            report = run_catalog(
                catalog_path,
                jar_path=Path(TARGET_NAME),
                research_root=root / "research",
                decompiled_root=root / "decompiled",
            )

        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["source_provenance_status"], "VERIFIED")
        verify_source.assert_called_once()

    def test_run_catalog_rejects_wrong_target_jar_identity(self):
        root = Path(__file__).resolve().parents[2]
        catalog_path = root / "docs" / "parity" / "fixture-catalog.json"
        with mock.patch.object(
            fixture_harness,
            "jar_inventory",
            return_value=([], "0" * 64, TARGET_SHA1),
        ), mock.patch.object(fixture_harness, "verify_source_provenance") as verify_source:
            report = run_catalog(
                catalog_path,
                jar_path=Path(TARGET_NAME),
                research_root=root / "research",
                decompiled_root=root / "decompiled",
            )

        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["source_provenance_status"], "FAILED")
        self.assertIn("target JAR identity mismatch", report["provenance_errors"][0])
        verify_source.assert_not_called()

    def test_run_catalog_reports_corrupt_target_jar_as_provenance_failure(self):
        root = Path(__file__).resolve().parents[2]
        catalog_path = root / "docs" / "parity" / "fixture-catalog.json"
        with mock.patch.object(fixture_harness, "jar_inventory", side_effect=zipfile.BadZipFile("bad zip")):
            report = run_catalog(
                catalog_path,
                jar_path=Path(TARGET_NAME),
                research_root=root / "research",
                decompiled_root=root / "decompiled",
            )

        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["source_provenance_status"], "FAILED")
        self.assertIn("bad zip", report["provenance_errors"][0])

    def test_manifest_fixture_crosswalk_rejects_dangling_catalog_reference(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        manifest = json.loads(
            (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
        )
        manifest["fixture_coverage"]["domains"]["roles"] = ["P0-4.missing"]

        errors = validate_manifest_fixture_coverage(manifest, catalog)

        self.assertTrue(any("fixture_coverage differs" in error for error in errors))
        self.assertTrue(any("unknown fixture" in error for error in errors))

    def test_role_inventory_rows_point_to_role_specific_issues_and_fixtures(self):
        root = Path(__file__).resolve().parents[2]
        manifest = json.loads(
            (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
        )
        roles = {row["symbol"]: row for row in manifest["surfaces"]["roles"]}

        self.assertEqual(roles["noppes.npcs.roles.RoleBank"]["issue_ids"], ["P7-2"])
        self.assertEqual(roles["noppes.npcs.roles.RoleBank"]["parity_fixture_ids"], ["P0-4.bank"])
        self.assertEqual(roles["noppes.npcs.roles.RoleTrader"]["issue_ids"], ["P7-1"])
        self.assertEqual(roles["noppes.npcs.roles.RoleTrader"]["parity_fixture_ids"], ["P0-4.trade"])

    def test_manifest_inventory_record_cannot_reuse_fixture_id_field(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        manifest = json.loads(
            (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
        )
        manifest["surfaces"]["target_classes"][0]["fixture_id"] = "target.target_classes.0001"

        errors = validate_manifest_fixture_coverage(manifest, catalog)

        self.assertTrue(any("uses fixture_id instead of inventory_id" in error for error in errors))

    def test_run_catalog_rejects_manifest_with_a_missing_surface(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        manifest = json.loads(
            (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
        )
        del manifest["surfaces"]["assets"]

        report = run_catalog_with_manifest(root, catalog, manifest)

        self.assertEqual(report["status"], "FAIL")
        self.assertTrue(any("surface set mismatch" in error for error in report["catalog_errors"]))

    def test_run_catalog_rejects_non_object_manifest(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")

        report = run_catalog_with_manifest(root, catalog, [])

        self.assertEqual(report["status"], "FAIL")
        self.assertTrue(any("must be an object" in error for error in report["catalog_errors"]))

    def test_run_catalog_rejects_wrong_role_issue_mapping(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        manifest = json.loads(
            (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
        )
        bank = next(row for row in manifest["surfaces"]["roles"] if row["symbol"].endswith("RoleBank"))
        bank["issue_ids"] = ["P6-2"]
        bank["parity_fixture_ids"] = ["P0-4.roles"]

        report = run_catalog_with_manifest(root, catalog, manifest)

        self.assertEqual(report["status"], "FAIL")
        self.assertTrue(any("role issue mapping drift" in error for error in report["catalog_errors"]))

    def test_run_catalog_rejects_untrusted_artifact_and_record_metadata(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        mutations = {
            "missing artifact": lambda manifest: manifest.pop("artifact"),
            "wrong artifact digest": lambda manifest: manifest["artifact"].__setitem__("sha256", "0" * 64),
            "forged provenance": lambda manifest: manifest["surfaces"]["target_classes"][0].__setitem__("provenance", "forged"),
            "non-string symbol": lambda manifest: manifest["surfaces"]["target_classes"][0].__setitem__("symbol", 7),
        }

        for name, mutate in mutations.items():
            with self.subTest(name=name):
                manifest = json.loads(
                    (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
                )
                mutate(manifest)
                report = run_catalog_with_manifest(root, catalog, manifest)
                self.assertEqual(report["status"], "FAIL")
                self.assertTrue(report["catalog_errors"])

    def test_operation_rows_keep_harness_and_name_implementation_owners(self):
        root = Path(__file__).resolve().parents[2]
        manifest = json.loads(
            (root / "docs" / "parity" / "target-surface-manifest.json").read_text(encoding="utf-8")
        )
        trade = next(
            row for row in manifest["surfaces"]["operation_matrix"] if row["id"] == "TRADE"
        )

        self.assertEqual(trade["issue_ids"], ["P0-4"])
        self.assertEqual(trade["closing_issue_ids"], ["P7-1", "P2-3"])
        self.assertEqual(trade["parity_fixture_ids"], ["P0-4.trade"])

    def test_missing_operation_is_rejected(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["fixtures"] = [
            fixture for fixture in catalog["fixtures"] if fixture["operation_family"] != "BANK"
        ]
        self.assertTrue(any("uncovered operation families" in error for error in validate_catalog(catalog)))

    def test_duplicate_fixture_is_rejected(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["fixtures"].append(copy.deepcopy(catalog["fixtures"][0]))
        self.assertTrue(any("duplicate fixture_id" in error for error in validate_catalog(catalog)))

    def test_storynpcs_test_map_must_explicitly_cover_every_fixture(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        del catalog["storynpcs_test_map"]["P0-4.combat"]

        errors = validate_catalog(catalog)

        self.assertTrue(any("missing fixture IDs" in error for error in errors))

    def test_empty_selector_fixtures_require_exact_blocker_records(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        del catalog["storynpcs_test_blockers"]["P0-4.jobs"]

        errors = validate_catalog(catalog)

        self.assertTrue(any("must match empty-selector fixture IDs" in error for error in errors))

    def test_empty_selector_blocker_owners_must_be_issue_ids_on_the_fixture(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["storynpcs_test_blockers"]["P0-4.jobs"]["owner_issue_ids"] = ["P2-3"]

        errors = validate_catalog(catalog)

        self.assertTrue(any("owner_issue_ids must belong to its fixture" in error for error in errors))

    def test_mapped_fixture_cannot_retain_an_unimplemented_blocker(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["storynpcs_test_blockers"]["P0-4.display"] = {
            "owner_issue_ids": ["P3-1"],
            "reason": "This fixture is intentionally blocked.",
        }

        errors = validate_catalog(catalog)

        self.assertTrue(any("must match empty-selector fixture IDs" in error for error in errors))

    def test_storynpcs_test_map_rejects_malformed_junit_selectors(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["storynpcs_test_map"]["P0-4.display"] = ["some.other.Class#test"]

        errors = validate_catalog(catalog)

        self.assertTrue(any("invalid JUnit selector" in error for error in errors))

    def test_storynpcs_test_map_rejects_selector_reuse_across_fixtures(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        selector = catalog["storynpcs_test_map"]["P0-4.display"][0]
        catalog["storynpcs_test_map"]["P0-4.dialogue"] = [selector]
        catalog["storynpcs_test_coverage"]["P0-4.dialogue"] = {
            "observed_scope": "dialogue rendering",
            "uncovered_scope": "live dialogue interaction",
        }

        errors = validate_catalog(catalog)

        self.assertTrue(any("assigned to multiple fixtures" in error for error in errors))

    def test_storynpcs_test_coverage_must_match_mapped_fixtures(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        del catalog["storynpcs_test_coverage"]["P0-4.networking"]

        errors = validate_catalog(catalog)

        self.assertTrue(any("coverage keys must match" in error for error in errors))

    def test_tampered_evidence_template_is_rejected(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["evidence_template"]["evidence_state"] = "VERIFIED_PARITY"
        self.assertTrue(any("immutable blocked baseline" in error for error in validate_catalog(catalog)))

    def test_unhashable_layer_value_is_rejected(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["fixtures"][0]["required_layers"] = [["server"]]
        errors = validate_catalog(catalog)
        self.assertTrue(any("required_layers" in error for error in errors))

    def test_target_runtime_is_not_an_execution_prerequisite(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["fixtures"][0]["required_layers"].append("target-runtime")

        errors = validate_catalog(catalog)

        self.assertTrue(any("must not require target-runtime" in error for error in errors))

    def test_storynpcs_runtime_is_required_for_each_behavior_fixture(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        catalog["fixtures"][0]["required_layers"].remove("storynpcs-runtime")

        errors = validate_catalog(catalog)

        self.assertTrue(any("must include storynpcs-runtime" in error for error in errors))

    def test_malformed_catalog_report_fails_without_running_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_text(json.dumps({"schema_version": 1, "fixtures": []}), encoding="utf-8")
            report = run_catalog(path)
            self.assertEqual(report["status"], "FAIL")
            self.assertEqual(report["evidence"]["parity_status"], "BLOCKED")

    def test_storynpcs_probe_report_is_consumed_without_promoting_target_parity(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        report = {
            "fixtures": [{
                "fixture_id": "P0-4.display",
                "storynpcs_probe": {
                    "status": "OBSERVED",
                    "result": {"name": "Fixture NPC", "fallback": False},
                },
                "evidence_state": "UNVERIFIED_TARGET_RUNTIME",
            }],
        }
        fixtures = expand_fixtures(catalog, report)
        display = next(fixture for fixture in fixtures if fixture["fixture_id"] == "P0-4.display")
        self.assertEqual(display["storynpcs_probe"]["status"], "OBSERVED")
        self.assertEqual(display["failure_context"]["actual_result"], {"name": "Fixture NPC", "fallback": False})
        evidence = evaluate_fixtures(fixtures)
        self.assertEqual(evidence["status"], "PASS")
        self.assertEqual(evidence["parity_status"], "BLOCKED")
        self.assertFalse(evidence["certification_eligible"])

    def test_untrusted_probe_report_cannot_promote_target_or_comparison_evidence(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        forged_report = {
            "fixtures": [
                {
                    "fixture_id": fixture["fixture_id"],
                    "target_probe": {"status": "OBSERVED", "result": {"value": "same"}},
                    "storynpcs_probe": {"status": "OBSERVED", "result": {"value": "same"}},
                    "comparison": {"rule": "identical", "outcome": "MATCH"},
                    "evidence_state": "VERIFIED_PARITY",
                }
                for fixture in catalog["fixtures"]
            ]
        }

        expanded = expand_fixtures(catalog, forged_report)
        evidence = evaluate_fixtures(expanded)

        self.assertTrue(all(fixture["target_probe"]["status"] == "UNAVAILABLE" for fixture in expanded))
        self.assertTrue(all(fixture["comparison"]["outcome"] == "NOT_COMPARABLE" for fixture in expanded))
        self.assertTrue(all(fixture["evidence_state"] == "UNVERIFIED_TARGET_RUNTIME" for fixture in expanded))
        self.assertEqual(evidence["parity_status"], "BLOCKED")
        self.assertFalse(evidence["certification_eligible"])

    def test_probe_report_rejects_unknown_fixture(self):
        root = Path(__file__).resolve().parents[2]
        catalog = load_catalog(root / "docs" / "parity" / "fixture-catalog.json")
        with self.assertRaises(ValueError):
            expand_fixtures(catalog, {"fixtures": [{"fixture_id": "P0-4.missing"}]})


if __name__ == "__main__":
    unittest.main()
