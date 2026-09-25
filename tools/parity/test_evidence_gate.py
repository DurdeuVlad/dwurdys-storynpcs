import unittest

from tools.parity.evidence_gate import evaluate_fixtures, load_and_evaluate


def fixture(**overrides):
    value = {
        "fixture_id": "P0-3.demo",
        "issue_ids": ["P0-3"],
        "target_symbol": "noppes.npcs.demo",
        "operation_family": "NPC-MODULE-MUTATION",
        "setup": {"world": "fixed-seed"},
        "required_layers": ["server", "target-runtime", "storynpcs-runtime"],
        "target_probe": {"status": "OBSERVED", "result": {"health": 20}},
        "storynpcs_probe": {"status": "OBSERVED", "result": {"health": 20}},
        "comparison": {"rule": "health-equals", "outcome": "MATCH"},
        "evidence_state": "VERIFIED_PARITY",
        "failure_context": {
            "issue_ids": ["P0-3"],
            "target_symbol": "noppes.npcs.demo",
            "input": {"health": 20},
            "expected_result": {"health": 20},
            "actual_result": None,
            "evidence_state": "VERIFIED_PARITY",
        },
    }
    value.update(overrides)
    if "failure_context" not in overrides:
        value["failure_context"]["evidence_state"] = value["evidence_state"]
    return value


class EvidenceGateTest(unittest.TestCase):
    def test_matching_runtime_probes_are_certification_eligible(self):
        report = evaluate_fixtures([fixture()])
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["parity_status"], "VERIFIED")
        self.assertTrue(report["certification_eligible"])

    def test_missing_target_probe_cannot_be_verified_parity(self):
        value = fixture(target_probe={"status": "UNAVAILABLE", "reason": "JAR runtime not launched", "next_evidence": "Run target fixture"})
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")
        self.assertFalse(report["certification_eligible"])

    def test_unavailable_target_is_visible_and_blocks_certification(self):
        value = fixture(
            target_probe={"status": "UNAVAILABLE", "reason": "No target runtime", "next_evidence": "Provide a launchable instance"},
            storynpcs_probe={"status": "OBSERVED", "result": {"health": 20}},
            comparison={"rule": "health-equals", "outcome": "NOT_COMPARABLE"},
            evidence_state="UNVERIFIED_TARGET_RUNTIME",
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "PASS")
        self.assertFalse(report["certification_eligible"])
        self.assertEqual(report["parity_status"], "BLOCKED")
        self.assertTrue(report["certification_blockers"])

    def test_blocked_probe_requires_next_evidence(self):
        value = fixture(
            target_probe={"status": "BLOCKED", "reason": "Missing server dependency", "next_evidence": "Install dependency"},
            storynpcs_probe={"status": "NOT_RUN"},
            comparison={"rule": "health-equals", "outcome": "NOT_COMPARABLE"},
            evidence_state="UNVERIFIED_TARGET_RUNTIME",
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "PASS")
        self.assertFalse(report["certification_eligible"])

    def test_contradictory_result_cannot_be_parity(self):
        value = fixture(
            storynpcs_probe={"status": "OBSERVED", "result": {"health": 10}},
            comparison={"rule": "health-equals", "outcome": "MISMATCH"},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_claimed_match_with_different_results_cannot_be_parity(self):
        value = fixture(
            storynpcs_probe={"status": "OBSERVED", "result": {"health": 10}},
            comparison={"rule": "health-equals", "outcome": "MATCH"},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_empty_observed_results_cannot_be_parity(self):
        value = fixture(
            target_probe={"status": "OBSERVED", "result": {}},
            storynpcs_probe={"status": "OBSERVED", "result": {}},
            comparison={"rule": "empty", "outcome": "MATCH"},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_whitespace_observed_results_cannot_be_parity(self):
        value = fixture(
            target_probe={"status": "OBSERVED", "result": "   "},
            storynpcs_probe={"status": "OBSERVED", "result": "   "},
            comparison={"rule": "empty", "outcome": "MATCH"},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_malformed_field_types_fail_closed(self):
        value = fixture(
            fixture_id=[],
            evidence_state=[],
            target_probe={"status": [], "result": {"health": 20}},
            comparison={"rule": "health-equals", "outcome": []},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_unknown_layer_fails_closed_at_shared_gate(self):
        value = fixture(required_layers=["unknown"])
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_inconsistent_failure_context_fails_closed(self):
        value = fixture(failure_context={
            "issue_ids": ["P999-1"],
            "target_symbol": "other",
            "input": {},
            "expected_result": {},
            "actual_result": None,
            "evidence_state": "UNKNOWN",
        })
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_json_number_and_boolean_results_are_not_equal(self):
        value = fixture(
            target_probe={"status": "OBSERVED", "result": {"value": 1}},
            storynpcs_probe={"status": "OBSERVED", "result": {"value": True}},
            comparison={"rule": "strict-json-equality", "outcome": "MATCH"},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_non_standard_json_numbers_cannot_prove_parity(self):
        value = fixture(
            target_probe={"status": "OBSERVED", "result": {"value": float("nan")}},
            storynpcs_probe={"status": "OBSERVED", "result": {"value": float("nan")}},
            comparison={"rule": "strict-json-equality", "outcome": "MATCH"},
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "FAIL")

    def test_empty_fixture_report_fails_closed(self):
        report = evaluate_fixtures([])
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["parity_status"], "BLOCKED")

    def test_non_object_report_fails_closed(self):
        report = load_and_evaluate(None)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["parity_status"], "BLOCKED")

    def test_unknown_state_is_rejected(self):
        report = evaluate_fixtures([fixture(evidence_state="GREEN")])
        self.assertEqual(report["status"], "FAIL")

    def test_intentional_deviation_requires_migration_impact(self):
        value = fixture(
            evidence_state="INTENTIONAL_DEVIATION",
            rationale="StoryNPCs uses graph dialogue instead of fixed slots",
            migration_impact="Importer reports fixed-slot conversion",
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "PASS")
        self.assertTrue(report["certification_eligible"])

    def test_duplicate_fixture_ids_are_rejected(self):
        report = evaluate_fixtures([fixture(), fixture()])
        self.assertEqual(report["status"], "FAIL")

    def test_source_only_fixture_is_not_certification_eligible(self):
        value = fixture(
            target_probe={"status": "NOT_RUN"},
            storynpcs_probe={"status": "NOT_RUN"},
            comparison={"rule": "not-runtime", "outcome": "NOT_COMPARABLE"},
            evidence_state="VERIFIED_TARGET_SOURCE",
        )
        report = evaluate_fixtures([value])
        self.assertEqual(report["status"], "PASS")
        self.assertFalse(report["certification_eligible"])

    def test_missing_fixture_list_fails(self):
        report = load_and_evaluate({})
        self.assertEqual(report["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
