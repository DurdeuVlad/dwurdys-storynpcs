import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.parity.run_storynpcs_fixtures import (
    build_probe_report,
    fixture_run_ci_check_passed,
    fixture_run_passed,
    gradle_test_command,
    _invalidate_previous_reports,
    main,
    read_junit_cases,
)
from tools.parity import run_storynpcs_fixtures


class StoryNpcsFixtureRunnerTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {
            "fixtures": [
                {"fixture_id": "P0-4.display", "issue_ids": ["P3-1"]},
                {"fixture_id": "P0-4.combat", "issue_ids": ["P3-2"]},
            ],
            "storynpcs_test_map": {
                "P0-4.display": ["com.storynpcs.client.render.DisplayTest#rendersName()"],
                "P0-4.combat": [],
            },
            "storynpcs_test_coverage": {
                "P0-4.display": {
                    "observed_scope": "name model renders",
                    "uncovered_scope": "live client render",
                },
            },
            "storynpcs_test_blockers": {
                "P0-4.combat": {
                    "owner_issue_ids": ["P3-2"],
                    "reason": "Combat behavior is not implemented; P3-2 owns the work.",
                },
            },
        }

    def test_gradle_command_clears_previous_test_reports_before_running(self):
        self.assertIn("cleanTest test --rerun-tasks", gradle_test_command())

    def test_same_report_path_is_rejected_without_deleting_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text('{"status":"PASS"}', encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "must resolve to different files"):
                _invalidate_previous_reports(report, report)

            self.assertEqual(report.read_text(encoding="utf-8"), '{"status":"PASS"}')

    def test_hardlink_alias_is_rejected_without_deleting_existing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            probe = Path(directory) / "probe.json"
            report.write_text('{"status":"PASS"}', encoding="utf-8")
            try:
                os.link(report, probe)
            except OSError as error:
                self.skipTest(f"hard links unavailable in temporary directory: {error}")

            with self.assertRaisesRegex(ValueError, "must resolve to different files"):
                _invalidate_previous_reports(probe, report)

            self.assertEqual(report.read_text(encoding="utf-8"), '{"status":"PASS"}')
            self.assertEqual(probe.read_text(encoding="utf-8"), '{"status":"PASS"}')

    def test_symlink_parent_alias_with_missing_leaf_is_rejected_before_unlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "real" / "future-report.json"
            probe = root / "alias" / "future-report.json"
            report.parent.mkdir()
            probe.parent.mkdir()
            report.write_text('{"status":"PASS"}', encoding="utf-8")
            probe.write_text('{"status":"PASS"}', encoding="utf-8")
            resolved_alias = report.resolve(strict=False)

            with mock.patch.object(
                Path,
                "resolve",
                autospec=True,
                side_effect=lambda path, strict=False: resolved_alias,
            ):
                with self.assertRaisesRegex(ValueError, "must resolve to different files"):
                    _invalidate_previous_reports(probe, report)

            self.assertEqual(report.read_text(encoding="utf-8"), '{"status":"PASS"}')
            self.assertEqual(probe.read_text(encoding="utf-8"), '{"status":"PASS"}')

    def test_secondary_output_invalidation_failure_does_not_leave_primary_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report_path = root / "fixture-report.json"
            probe_path = root / "probe-report.json"
            report_path.write_text('{"status":"PASS"}', encoding="utf-8")
            probe_path.write_text('{"fixtures":[{"storynpcs_probe":{"status":"OBSERVED"}}]}', encoding="utf-8")
            original_unlink = Path.unlink

            def fail_probe_unlink(path, *args, **kwargs):
                if path == probe_path:
                    raise PermissionError("simulated probe report lock")
                return original_unlink(path, *args, **kwargs)

            args = type("Args", (), {
                "catalog": root / "catalog.json",
                "probe_report": probe_path,
                "report": report_path,
            })()
            with (
                mock.patch.object(run_storynpcs_fixtures, "parse_args", return_value=args),
                mock.patch.object(Path, "unlink", fail_probe_unlink),
                mock.patch.object(run_storynpcs_fixtures, "subprocess") as subprocess_mock,
            ):
                self.assertEqual(main(), 1)

            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "FAIL")
            self.assertEqual(report["error"].split(":")[0], "Could not prepare fresh fixture report outputs; Gradle was not started")
            probe_report = json.loads(probe_path.read_text(encoding="utf-8"))
            self.assertEqual(probe_report, {"run_state": "OUTPUT_PREFLIGHT_FAILED", "fixtures": []})
            subprocess_mock.run.assert_not_called()

    def test_passed_junit_case_is_fixture_keyed_but_not_target_parity(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-com.storynpcs.client.render.DisplayTest.xml").write_text(
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" '
                'name="rendersName()" time="0.01"/></testsuite>',
                encoding="utf-8",
            )
            cases = read_junit_cases(reports)

        report, counts, missing = build_probe_report(self.catalog, cases)

        display = report["fixtures"][0]
        self.assertEqual(display["fixture_id"], "P0-4.display")
        self.assertEqual(display["storynpcs_probe"]["status"], "OBSERVED")
        self.assertEqual(display["storynpcs_probe"]["result"]["outcome"], "PASSED")
        self.assertIn("not a live Minecraft", display["storynpcs_probe"]["result"]["execution_scope"])
        self.assertEqual(display["storynpcs_probe"]["result"]["observed_scope"], "name model renders")
        self.assertEqual(display["storynpcs_probe"]["result"]["uncovered_scope"], "live client render")
        self.assertIn("do not certify", display["storynpcs_probe"]["result"]["claim_limit"])
        self.assertEqual(display["evidence_state"], "UNVERIFIED_TARGET_RUNTIME")
        self.assertEqual(report["fixtures"][1]["storynpcs_probe"]["status"], "BLOCKED")
        self.assertEqual(
            report["fixtures"][1]["storynpcs_probe"]["owner_issue_ids"], ["P3-2"]
        )
        self.assertIn("P3-2", report["fixtures"][1]["storynpcs_probe"]["next_evidence"])
        self.assertEqual(counts, {
            "observed": 1,
            "blocked": 1,
            "blocked_mapped": 0,
            "unmapped": 1,
            "failed": 0,
            "mapped_cases": 1,
            "fixture_count": 2,
        })
        self.assertEqual(missing, [])
        self.assertFalse(fixture_run_passed(0, {"status": "PASS"}, counts, []))

    def test_ci_check_passes_only_for_declared_empty_maps_and_keeps_parity_blocked(self):
        selector = "com.storynpcs.client.render.DisplayTest#rendersName()"
        cases = {
            selector: {
                "selector": selector,
                "class_name": "com.storynpcs.client.render.DisplayTest",
                "test_name": "rendersName()",
                "outcome": "PASSED",
            },
        }
        probe_report, counts, missing = build_probe_report(self.catalog, cases)
        fixture_report = {
            "status": "BLOCKED",
            "validation_status": "PASS",
            "source_provenance_status": "UNVERIFIED",
            "provenance_blockers": [
                "--jar was not supplied; target source provenance was not checked",
                "--research-root was not supplied; target source provenance was not checked",
                "--decompiled-root was not supplied; target source provenance was not checked",
            ],
            "storynpcs_execution_coverage": "INCOMPLETE",
            "catalog_errors": [],
            "coverage": {
                "fixture_count": 2,
                "mapped_junit_fixture_count": 1,
                "unmapped_junit_fixture_ids": ["P0-4.combat"],
                "unmapped_junit_fixture_blockers": [{
                    "fixture_id": "P0-4.combat",
                    "owner_issue_ids": ["P3-2"],
                    "reason": "Combat behavior is not implemented; P3-2 owns the work.",
                }],
            },
            "evidence": {
                "status": "PASS",
                "errors": [],
                "parity_status": "BLOCKED",
                "certification_eligible": False,
            },
        }

        self.assertTrue(fixture_run_ci_check_passed(
            0, fixture_report, probe_report, counts, missing, self.catalog
        ))
        self.assertFalse(fixture_run_passed(0, fixture_report, counts, missing))
        self.assertEqual(fixture_report["evidence"]["parity_status"], "BLOCKED")

    def test_ci_check_rejects_unmapped_or_failed_mapped_test_drift(self):
        selector = "com.storynpcs.client.render.DisplayTest#rendersName()"
        cases = {
            selector: {
                "selector": selector,
                "class_name": "com.storynpcs.client.render.DisplayTest",
                "test_name": "rendersName()",
                "outcome": "PASSED",
            },
        }
        probe_report, counts, missing = build_probe_report(self.catalog, cases)
        fixture_report = {
            "status": "BLOCKED",
            "validation_status": "PASS",
            "source_provenance_status": "UNVERIFIED",
            "catalog_errors": [],
            "coverage": {"unmapped_junit_fixture_ids": ["P0-4.combat"]},
            "evidence": {"parity_status": "BLOCKED", "certification_eligible": False},
        }

        changed_counts = dict(counts, failed=1)
        self.assertFalse(fixture_run_ci_check_passed(
            0, fixture_report, probe_report, changed_counts, missing, self.catalog
        ))
        self.assertFalse(fixture_run_ci_check_passed(
            1, fixture_report, probe_report, counts, missing, self.catalog
        ))
        failed_provenance = dict(fixture_report, source_provenance_status="FAILED")
        self.assertFalse(fixture_run_ci_check_passed(
            0, failed_provenance, probe_report, counts, missing, self.catalog
        ))
        unexpected_blocker = json.loads(json.dumps(fixture_report))
        unexpected_blocker["coverage"]["unmapped_junit_fixture_ids"].append("P0-4.display")
        self.assertFalse(fixture_run_ci_check_passed(
            0, unexpected_blocker, probe_report, counts, missing, self.catalog
        ))
        altered_probe = json.loads(json.dumps(probe_report))
        altered_probe["fixtures"][0]["storynpcs_probe"]["result"]["outcome"] = "FAILED"
        self.assertFalse(fixture_run_ci_check_passed(
            0, fixture_report, altered_probe, counts, missing, self.catalog
        ))
        mismapped_probe = json.loads(json.dumps(probe_report))
        mismapped_probe["fixtures"][0]["storynpcs_probe"]["result"]["tests"][0]["selector"] = (
            "com.storynpcs.client.render.DisplayTest#unrelatedCase()"
        )
        self.assertFalse(fixture_run_ci_check_passed(
            0, fixture_report, mismapped_probe, counts, missing, self.catalog
        ))
        invalid_evidence = json.loads(json.dumps(fixture_report))
        invalid_evidence["evidence"]["errors"] = [{"message": "unexpected fixture error"}]
        self.assertFalse(fixture_run_ci_check_passed(
            0, invalid_evidence, probe_report, counts, missing, self.catalog
        ))

    def test_ci_mode_reports_blocked_parity_but_passes_declared_test_gate(self):
        selector = "com.storynpcs.client.render.DisplayTest#rendersName()"
        cases = {
            selector: {
                "selector": selector,
                "class_name": "com.storynpcs.client.render.DisplayTest",
                "test_name": "rendersName()",
                "outcome": "PASSED",
            },
        }
        fixture_report = {
            "status": "BLOCKED",
            "validation_status": "PASS",
            "source_provenance_status": "UNVERIFIED",
            "provenance_blockers": [
                "--jar was not supplied; target source provenance was not checked",
                "--research-root was not supplied; target source provenance was not checked",
                "--decompiled-root was not supplied; target source provenance was not checked",
            ],
            "storynpcs_execution_coverage": "INCOMPLETE",
            "catalog_errors": [],
            "coverage": {
                "fixture_count": 2,
                "mapped_junit_fixture_count": 1,
                "unmapped_junit_fixture_ids": ["P0-4.combat"],
                "unmapped_junit_fixture_blockers": [{
                    "fixture_id": "P0-4.combat",
                    "owner_issue_ids": ["P3-2"],
                    "reason": "Combat behavior is not implemented; P3-2 owns the work.",
                }],
            },
            "evidence": {
                "status": "PASS",
                "errors": [],
                "parity_status": "BLOCKED",
                "certification_eligible": False,
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = type("Args", (), {
                "catalog": root / "catalog.json",
                "probe_report": root / "probe.json",
                "report": root / "fixture-report.json",
                "jar": None,
                "research_root": None,
                "decompiled_root": None,
                "ci_allow_declared_blockers": True,
            })()
            with (
                mock.patch.object(run_storynpcs_fixtures, "parse_args", return_value=args),
                mock.patch.object(run_storynpcs_fixtures, "subprocess") as subprocess_mock,
                mock.patch.object(run_storynpcs_fixtures, "load_catalog", return_value=self.catalog),
                mock.patch.object(run_storynpcs_fixtures, "read_junit_cases", return_value=cases),
                mock.patch.object(run_storynpcs_fixtures, "run_catalog", return_value=fixture_report),
            ):
                subprocess_mock.run.return_value.returncode = 0
                self.assertEqual(main(), 0)

            report = json.loads(args.report.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "BLOCKED")
            self.assertEqual(report["ci_validation_status"], "PASS")
            self.assertEqual(report["ci_allowed_blockers"], ["P0-4.combat"])
            self.assertEqual(report["parity_status"], "BLOCKED")

    def test_missing_mapped_case_is_blocked_and_reported_as_mapping_drift(self):
        report, counts, missing = build_probe_report(self.catalog, {})

        self.assertEqual(report["fixtures"][0]["storynpcs_probe"]["status"], "BLOCKED")
        self.assertEqual(counts["blocked"], 2)
        self.assertEqual(missing, ["com.storynpcs.client.render.DisplayTest#rendersName()"])

    def test_probe_report_rejects_one_selector_claimed_by_multiple_fixtures(self):
        catalog = json.loads(json.dumps(self.catalog))
        selector = catalog["storynpcs_test_map"]["P0-4.display"][0]
        catalog["storynpcs_test_map"]["P0-4.combat"] = [selector]
        catalog["storynpcs_test_coverage"]["P0-4.combat"] = {
            "observed_scope": "combat behavior",
            "uncovered_scope": "live combat",
        }
        case = {
            "selector": selector,
            "class_name": "com.storynpcs.client.render.DisplayTest",
            "test_name": "rendersName()",
            "outcome": "PASSED",
        }

        with self.assertRaisesRegex(ValueError, "assigned to multiple fixtures"):
            build_probe_report(catalog, {selector: case})

    def test_failed_junit_case_is_preserved_as_an_observed_failure(self):
        case = {
            "selector": "com.storynpcs.client.render.DisplayTest#rendersName()",
            "class_name": "com.storynpcs.client.render.DisplayTest",
            "test_name": "rendersName()",
            "outcome": "FAILED",
            "failure_detail": "expected visible name",
        }

        report, counts, missing = build_probe_report(self.catalog, {case["selector"]: case})

        self.assertEqual(report["fixtures"][0]["storynpcs_probe"]["result"]["outcome"], "FAILED")
        self.assertEqual(report["fixtures"][0]["storynpcs_probe"]["result"]["tests"][0]["failure_detail"],
                         "expected visible name")
        self.assertEqual(counts["failed"], 1)
        self.assertEqual(missing, [])

    def test_malformed_junit_xml_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-bad.xml").write_text("<testsuite>", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "malformed JUnit report"):
                read_junit_cases(reports)

    def test_junit_suite_totals_must_match_contained_test_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-mismatch.xml").write_text(
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="0" failures="0" errors="0" skipped="0">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()"/>'
                '</testsuite>',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "JUnit suite totals disagree"):
                read_junit_cases(reports)

    def test_junit_suite_totals_reconcile_failure_outcomes(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-failure-count.xml").write_text(
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1" failures="0" errors="0" skipped="0">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()">'
                '<failure message="expected name">missing</failure></testcase></testsuite>',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "failures declares 0, observed 1"):
                read_junit_cases(reports)

    def test_nested_junit_suites_reconcile_aggregate_and_leaf_totals(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-nested.xml").write_text(
                '<testsuites tests="2" failures="1" errors="0" skipped="0">'
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1" failures="0" errors="0" skipped="0">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()"/>'
                '</testsuite>'
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1" failures="1" errors="0" skipped="0">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersFallback()">'
                '<failure message="unexpected fallback"/></testcase></testsuite></testsuites>',
                encoding="utf-8",
            )

            cases = read_junit_cases(reports)

        self.assertEqual(len(cases), 2)
        self.assertEqual(cases["com.storynpcs.client.render.DisplayTest#rendersName()"]["outcome"], "PASSED")
        self.assertEqual(cases["com.storynpcs.client.render.DisplayTest#rendersFallback()"]["outcome"], "FAILED")

    def test_nested_outcome_marker_in_testcase_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-nested-failure.xml").write_text(
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1" failures="0" errors="0" skipped="0">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()">'
                '<wrapper><failure message="nested failure"/></wrapper></testcase></testsuite>',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "outcome markers must be direct children"):
                read_junit_cases(reports)

    def test_testcase_hidden_in_suite_wrapper_is_rejected_not_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-hidden-failure.xml").write_text(
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="2" failures="1" errors="0" skipped="0">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()"/>'
                '<wrapper><testcase classname="com.storynpcs.client.render.DisplayTest" name="hiddenFailure()">'
                '<failure message="hidden failure"/></testcase></wrapper></testsuite>',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "testcase must be a direct child of testsuite"):
                read_junit_cases(reports)

    def test_skipped_mapped_case_does_not_count_as_observed(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-com.storynpcs.client.render.DisplayTest.xml").write_text(
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1" skipped="1">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()">'
                '<skipped/></testcase></testsuite>',
                encoding="utf-8",
            )
            cases = read_junit_cases(reports)

        report, counts, _ = build_probe_report(self.catalog, cases)

        self.assertEqual(report["fixtures"][0]["storynpcs_probe"]["status"], "BLOCKED")
        self.assertEqual(report["fixtures"][1]["storynpcs_probe"]["status"], "BLOCKED")
        self.assertEqual(counts["observed"], 0)
        self.assertEqual(counts["blocked_mapped"], 1)
        self.assertFalse(fixture_run_passed(0, {"status": "PASS"}, counts, []))

    def test_duplicate_junit_selectors_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            body = (
                '<testsuite name="com.storynpcs.client.render.DisplayTest" tests="1">'
                '<testcase classname="com.storynpcs.client.render.DisplayTest" name="rendersName()"/>'
                '</testsuite>'
            )
            (reports / "TEST-a.xml").write_text(body, encoding="utf-8")
            (reports / "TEST-b.xml").write_text(body, encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "duplicate JUnit test case selector"):
                read_junit_cases(reports)

    def test_missing_junit_reports_after_clean_test_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "no Gradle JUnit XML reports"):
                read_junit_cases(Path(directory))

    def test_gradle_failure_overwrites_old_pass_and_never_parses_junit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report_path = root / "fixture-report.json"
            probe_path = root / "probe-report.json"
            report_path.write_text('{"status":"PASS"}', encoding="utf-8")
            probe_path.write_text('{"fixtures":[{"storynpcs_probe":{"status":"OBSERVED"}}]}', encoding="utf-8")
            args = type("Args", (), {
                "catalog": root / "catalog.json",
                "junit_results": root / "missing-junit",
                "probe_report": probe_path,
                "report": report_path,
            })()

            def fail_after_checking_outputs_were_invalidated(command, **kwargs):
                self.assertFalse(report_path.exists())
                self.assertFalse(probe_path.exists())
                return type("GradleResult", (), {"returncode": 1})()

            with (
                mock.patch.object(run_storynpcs_fixtures, "parse_args", return_value=args),
                mock.patch.object(run_storynpcs_fixtures, "subprocess") as subprocess_mock,
                mock.patch.object(run_storynpcs_fixtures, "load_catalog") as load_catalog_mock,
                mock.patch.object(run_storynpcs_fixtures, "read_junit_cases") as read_junit_mock,
            ):
                subprocess_mock.run.side_effect = fail_after_checking_outputs_were_invalidated
                self.assertEqual(main(), 1)

            report = json.loads(report_path.read_text(encoding="utf-8"))
            probe_report = json.loads(probe_path.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "FAIL")
            self.assertFalse(report["evidence_ingested"])
            self.assertIsNone(report["fixture_report"])
            self.assertEqual(probe_report, {"run_state": "GRADLE_FAILED", "fixtures": []})
            load_catalog_mock.assert_not_called()
            read_junit_mock.assert_not_called()

    def test_adapter_failure_after_gradle_success_overwrites_old_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report_path = root / "fixture-report.json"
            probe_path = root / "probe-report.json"
            report_path.write_text('{"status":"PASS"}', encoding="utf-8")
            probe_path.write_text('{"fixtures":[{"storynpcs_probe":{"status":"OBSERVED"}}]}', encoding="utf-8")
            args = type("Args", (), {
                "catalog": root / "catalog.json",
                "probe_report": probe_path,
                "report": report_path,
            })()

            with (
                mock.patch.object(run_storynpcs_fixtures, "parse_args", return_value=args),
                mock.patch.object(run_storynpcs_fixtures, "subprocess") as subprocess_mock,
                mock.patch.object(run_storynpcs_fixtures, "load_catalog", return_value=self.catalog),
                mock.patch.object(
                    run_storynpcs_fixtures,
                    "read_junit_cases",
                    side_effect=ValueError("no Gradle JUnit XML reports found"),
                ),
            ):
                subprocess_mock.run.return_value.returncode = 0
                self.assertEqual(main(), 1)

            report = json.loads(report_path.read_text(encoding="utf-8"))
            probe_report = json.loads(probe_path.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "FAIL")
            self.assertFalse(report["evidence_ingested"])
            self.assertIsNone(report["fixture_report"])
            self.assertEqual(probe_report, {"run_state": "ADAPTER_FAILED", "fixtures": []})


if __name__ == "__main__":
    unittest.main()
