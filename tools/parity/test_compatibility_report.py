#!/usr/bin/env python3
"""Tests for tools/parity/compatibility_report.py (P11-2)."""

from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from tools.parity.compatibility_report import expand_compatibility

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "docs" / "parity" / "target-surface-manifest.json"
MAP_PATH = ROOT / "docs" / "parity" / "storynpcs-surface-map.json"


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


class CompatibilityReportTest(unittest.TestCase):

    def setUp(self):
        self.manifest = load(MANIFEST_PATH)
        self.surface_map = load(MAP_PATH)

    def test_checked_in_map_covers_every_manifest_row(self):
        report = expand_compatibility(self.manifest, self.surface_map)
        self.assertEqual(report["validation_status"], "PASS", report["errors"][:5])
        self.assertEqual(report["summary"]["unmapped_rows"], 0)
        total = self.manifest["counts"]
        self.assertEqual(report["summary"]["total_rows"],
                         sum(total[k] for k in (
                             "classes", "assets", "data", "gui", "packets", "commands",
                             "events", "roles", "jobs", "companion_jobs",
                             "persistence_participants", "persistence_stores", "operations")))
        # Every behavioral row's parity claim is blocked by the missing target probe.
        blocked = report["summary"]["parity_blocked_rows"]
        mapped = report["summary"]["by_state"]
        self.assertEqual(blocked, mapped.get("MAPPED_STORYNPCS_OBSERVED", 0)
                         + mapped.get("INTENTIONAL_DEVIATION", 0)
                         + mapped.get("UNVERIFIED_STORYNPCS", 0)
                         + mapped.get("UNKNOWN", 0))
        # Known deviations are declared.
        deviation_states = report["summary"]["by_state"]
        self.assertGreater(deviation_states.get("INTENTIONAL_DEVIATION", 0), 0)
        self.assertGreater(deviation_states.get("INVENTORY_ONLY", 0), 0)

    def test_removed_surface_default_leaves_rows_unmapped(self):
        broken = copy.deepcopy(self.surface_map)
        del broken["surface_defaults"]["commands"]
        report = expand_compatibility(self.manifest, broken)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("unmapped target row" in e for e in report["errors"]))
        self.assertEqual(report["summary"]["unmapped_rows"],
                         self.manifest["counts"]["commands"])

    def test_deviation_requires_declared_deviation_id(self):
        broken = copy.deepcopy(self.surface_map)
        broken["surface_defaults"]["commands"]["deviation_id"] = "missing-deviation"
        report = expand_compatibility(self.manifest, broken)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("unknown deviation_id" in e for e in report["errors"]))

    def test_deviation_requires_rationale_and_migration_impact(self):
        broken = copy.deepcopy(self.surface_map)
        for d in broken["deviations"]:
            if d["deviation_id"] == "command-grammar":
                d["rationale"] = ""
        report = expand_compatibility(self.manifest, broken)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("missing rationale" in e for e in report["errors"]))

    def test_unknown_state_requires_evidence(self):
        broken = copy.deepcopy(self.surface_map)
        broken["surface_defaults"]["data"] = {"mapping_state": "UNKNOWN"}
        report = expand_compatibility(self.manifest, broken)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("evidence_required" in e for e in report["errors"]))

    def test_row_override_wins_over_surface_default(self):
        modified = copy.deepcopy(self.surface_map)
        modified["row_overrides"].append({
            "inventory_id": "target.data.0001",
            "mapping_state": "UNVERIFIED_STORYNPCS",
            "storynpcs_ref": "",
            "evidence_required": "damage_type analog must be observed before certification",
        })
        report = expand_compatibility(self.manifest, modified)
        self.assertEqual(report["validation_status"], "PASS", report["errors"][:5])
        row = next(r for r in report["rows"] if r["inventory_id"] == "target.data.0001")
        self.assertEqual(row["mapping_state"], "UNVERIFIED_STORYNPCS")
        self.assertEqual(row["parity_state"], "UNVERIFIED_TARGET_RUNTIME")

    def test_mapped_rows_must_name_verifiable_artifacts(self):
        # Issue #124: milestone-label refs ("P8-3: namespaced assets...") are
        # capability claims, not artifacts — they must fail the rule. The data
        # surface has no row_overrides, so its default applies to every row.
        broken = copy.deepcopy(self.surface_map)
        broken["surface_defaults"]["data"] = {
            "mapping_state": "MAPPED_STORYNPCS_OBSERVED",
            "storynpcs_ref": "P2-1: versioned YAML families with envelope",
        }
        report = expand_compatibility(self.manifest, broken, ROOT)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("unverifiable ref token" in e for e in report["errors"]))

    def test_mapped_row_with_bogus_artifact_path_fails(self):
        broken = copy.deepcopy(self.surface_map)
        broken["row_overrides"].append({
            "inventory_id": "target.data.0001",
            "mapping_state": "MAPPED_STORYNPCS_OBSERVED",
            "storynpcs_ref": "class:com.storynpcs.doesnotexist.GhostClass "
                             "path:docs/parity/no-such-file.json",
        })
        report = expand_compatibility(self.manifest, broken, ROOT)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("no java source" in e for e in report["errors"]))
        self.assertTrue(any("path does not exist" in e for e in report["errors"]))

    def test_mapped_row_with_bogus_operation_fails(self):
        broken = copy.deepcopy(self.surface_map)
        broken["row_overrides"].append({
            "inventory_id": "target.data.0001",
            "mapping_state": "MAPPED_STORYNPCS_OBSERVED",
            "storynpcs_ref": "op:not.a.real.operation",
        })
        report = expand_compatibility(self.manifest, broken, ROOT)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("not a registered canonical operation" in e
                            for e in report["errors"]))

    def test_path_tokens_must_be_repo_confined_and_nonempty(self):
        from tools.parity.compatibility_report import verify_storynpcs_ref
        for bad in ("path:", "path:.", "path:src/../..", "path:../outside",
                    "path:C:/Windows", "path:/etc/hostname"):
            problems = verify_storynpcs_ref(bad, ROOT)
            self.assertTrue(problems, f"{bad} should not verify")
        self.assertEqual(
            verify_storynpcs_ref("path:docs/parity/storynpcs-surface-map.json", ROOT), [])

    def test_test_kind_resolves_only_test_sources(self):
        from tools.parity.compatibility_report import verify_storynpcs_ref
        # A main-source class is not a test artifact.
        problems = verify_storynpcs_ref(
            "test:com.storynpcs.service.CapabilityRegistry", ROOT)
        self.assertTrue(any("no java test source" in p for p in problems))
        self.assertEqual(
            verify_storynpcs_ref("test:com.storynpcs.service.TemplateMutationTest", ROOT), [])

    def test_checked_in_refs_all_resolve_and_markovnames_are_corrected(self):
        # Every MAPPED row's tokens resolve; markovnames data rows — the case
        # that motivated #124 — are INVENTORY_ONLY.
        report = expand_compatibility(self.manifest, self.surface_map, ROOT)
        self.assertEqual(report["validation_status"], "PASS", report["errors"][:5])
        integrity = report["mapping_integrity"]
        self.assertEqual(integrity["failed_refs"], [])
        self.assertEqual(integrity["mapped_rows_checked"],
                         report["summary"]["by_state"]["MAPPED_STORYNPCS_OBSERVED"])
        self.assertGreater(integrity["verified_ref_tokens"], 0)
        markov = [r for r in report["rows"]
                  if r["surface"] == "data" and "markovnames" in str(r["symbol"])]
        self.assertEqual(len(markov), 19)
        self.assertTrue(all(r["mapping_state"] == "INVENTORY_ONLY" for r in markov))
        # Summary stays terminal.
        self.assertNotIn("UNKNOWN", report["summary"]["by_state"])
        self.assertEqual(report["summary"]["unmapped_rows"], 0)

    def test_duplicate_inventory_id_fails(self):
        broken = copy.deepcopy(self.manifest)
        broken["surfaces"]["roles"].append(dict(broken["surfaces"]["roles"][0]))
        report = expand_compatibility(broken, self.surface_map)
        self.assertEqual(report["validation_status"], "FAIL")
        self.assertTrue(any("duplicate inventory_id" in e for e in report["errors"]))


if __name__ == "__main__":
    unittest.main()
