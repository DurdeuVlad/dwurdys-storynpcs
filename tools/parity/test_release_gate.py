#!/usr/bin/env python3
"""Tests for tools/parity/release_gate.py (P11-3)."""

from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from tools.parity import release_gate

ROOT = Path(__file__).resolve().parents[2]
PARITY = ROOT / "docs" / "parity"


class ReleaseGateTest(unittest.TestCase):

    def test_unverified_target_runtime_keeps_release_gate_blocked(self):
        report = release_gate.run_gate(ROOT)
        self.assertEqual(report["gate_status"], "BLOCKED")
        self.assertTrue(report["checks"]["storynpcs_fixture_execution"]["pass"],
                        report["checks"]["storynpcs_fixture_execution"]["findings"])
        self.assertTrue(report["checks"]["p11_3_status"]["pass"])
        self.assertTrue(report["checks"]["p11_3_status"]["blocked"])
        self.assertFalse(report["checks"]["target_runtime_evidence"]["pass"])
        self.assertTrue(report["checks"]["target_runtime_evidence"]["blocked"])

    def test_in_review_is_not_a_terminal_release_state(self):
        self.assertNotIn("IN-REVIEW", release_gate.DONE_STATES)
        self.assertIn("IN-REVIEW", release_gate.UNFINISHED_STATES)

    def test_stale_fixture_evidence_cannot_pass_the_release_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report_path = root / "docs" / "parity" / "reports" / "storynpcs-fixture-report.json"
            report_path.parent.mkdir(parents=True)
            report_path.write_text(json.dumps({
                "source_fingerprint": "stale",
                "gradle_exit_code": 0,
                "ci_validation_status": "PASS",
                "missing_test_selectors": [],
                "junit_test_case_count": 1,
                "storynpcs_fixture_counts": {
                    "fixture_count": 1, "observed": 1, "blocked": 0,
                    "blocked_mapped": 0, "failed": 0, "unmapped": 0,
                },
                "storynpcs_probe_report": {"fixtures": [{
                    "storynpcs_probe": {"status": "OBSERVED", "result": {"outcome": "PASSED"}}
                }]},
                "fixture_report": {
                    "validation_status": "PASS",
                    "evidence": {"parity_status": "BLOCKED", "certification_eligible": False},
                },
            }), encoding="utf-8")

            local, runtime = release_gate.fixture_evidence_checks(root, 1)

            self.assertFalse(local["pass"])
            self.assertTrue(local["blocked"])
            self.assertFalse(runtime["pass"])
            self.assertTrue(runtime["blocked"])

    def test_register_parses_unique_issues_and_milestones(self):
        register = release_gate.parse_register(
            ROOT / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md")
        self.assertEqual(register["duplicates"], [])
        self.assertEqual(len(register["issues"]), 47)
        self.assertIn("M11", register["milestones"])

    def test_milestone_shorthand_expands_to_registered_ids(self):
        register = release_gate.parse_register(
            ROOT / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md")
        body = "**Dependencies and open decisions:** M0-M1, P2-3."
        deps, errors = release_gate.expand_dependencies(body, register["milestones"])
        self.assertEqual(errors, [])
        self.assertIn("P0-1", deps)
        self.assertIn("P1-4", deps)
        self.assertIn("P2-3", deps)
        self.assertNotIn("P11-3", deps)

    def test_dangling_dependency_detected(self):
        register = {"issues": {"P1-1": "**Dependencies and open decisions:** P9-9."},
                    "milestones": {"M1": ["P1-1"]}}
        order = {"P1-1": 0}
        graph, errors, _fwd = release_gate.dependency_graph(register, order)
        self.assertTrue(any("dangling dependency P9-9" in e for e in errors))

    def test_cycle_detected_in_backward_graph(self):
        register = {"issues": {
                        "P1-1": "**Dependencies and open decisions:** P1-2.",
                        "P1-2": "**Dependencies and open decisions:** P1-1."},
                    "milestones": {"M1": ["P1-1", "P1-2"]}}
        order = {"P1-1": 0, "P1-2": 1}
        graph, errors, _fwd = release_gate.dependency_graph(register, order)
        # P1-1 -> P1-2 is a forward reference (warn); P1-2 -> P1-1 is backward.
        cycles = release_gate.find_cycles({"A": {"B"}, "B": {"A"}})
        self.assertEqual(len(cycles), 1)

    def test_forward_references_are_not_cycles(self):
        register = release_gate.parse_register(
            ROOT / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md")
        order = {issue: i for i, issue in enumerate(
            release_gate.flow_order(
                ROOT / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md",
                register["milestones"]))}
        graph, errors, forward = release_gate.dependency_graph(register, order)
        self.assertEqual(errors, [])
        self.assertEqual(release_gate.find_cycles(graph), [])
        self.assertTrue(any(f.startswith("P0-4") for f in forward))

    def test_forward_references_serialize_in_sorted_order(self):
        # Set-iteration order must not leak into the report: identical inputs
        # must yield byte-identical release-gate output across runs/platforms.
        register = {"issues": {
                        "P1-1": "**Dependencies and open decisions:** P9-1, P9-3, P9-2.",
                        "P9-1": "", "P9-2": "", "P9-3": ""},
                    "milestones": {"M1": ["P1-1"], "M9": ["P9-1", "P9-2", "P9-3"]}}
        order = {"P1-1": 0, "P9-1": 1, "P9-2": 2, "P9-3": 3}
        _graph, errors, forward = release_gate.dependency_graph(register, order)
        self.assertEqual(errors, [])
        # Exact expected list, not just sortedness — a lucky hash seed must not
        # be able to mask nondeterministic set-iteration order (#127).
        self.assertEqual(forward, [
            "P1-1 -> P9-1 (forward ownership/fixture reference)",
            "P1-1 -> P9-2 (forward ownership/fixture reference)",
            "P1-1 -> P9-3 (forward ownership/fixture reference)",
        ])

    def test_write_report_emits_lf_bytes_on_every_platform(self):
        report = release_gate.run_gate(ROOT)
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "release-gate-report.json"
            release_gate.write_report(report, out)
            raw = out.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))

    def test_report_forward_references_are_sorted(self):
        report = release_gate.run_gate(ROOT)
        forward = report["checks"]["dependency_graph"]["detail"]["forward_references"]
        self.assertEqual(forward, sorted(forward))

    def test_issue_statuses_cover_all_issues(self):
        register = release_gate.parse_register(
            ROOT / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md")
        statuses = release_gate.issue_status(register, PARITY)
        self.assertEqual(set(statuses), set(register["issues"]))
        for issue, status in statuses.items():
            self.assertIn(status, release_gate.DONE_STATES
                          | release_gate.BLOCKED_STATES
                          | release_gate.UNFINISHED_STATES,
                          f"{issue}: {status}")
        self.assertEqual(statuses["P11-3"], "BLOCKED")


if __name__ == "__main__":
    unittest.main()
