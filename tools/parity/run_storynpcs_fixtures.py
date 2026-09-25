#!/usr/bin/env python3
"""Run StoryNPCs JUnit fixtures and report fixture-ID-keyed observations.

The report records JVM test outcomes only. It does not claim a live Minecraft,
CustomNPCs target-runtime, GUI, or client/server observation.
"""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from typing import Any

try:
    from tools.parity.fixture_harness import load_catalog, run_catalog
except ModuleNotFoundError:  # Direct execution: python tools/parity/run_storynpcs_fixtures.py
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.parity.fixture_harness import load_catalog, run_catalog


MAX_JUNIT_REPORTS = 1024
MAX_JUNIT_REPORT_BYTES = 16 * 1024 * 1024
MAX_JUNIT_CASES = 100_000
MAX_FAILURE_DETAIL = 1024
JUNIT_CLASS_PATTERN = re.compile(r"^com\.storynpcs(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$")


def _xml_local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _case_outcome_markers(case: ET.Element) -> list[ET.Element]:
    allowed = {"failure", "error", "skipped"}
    markers: list[ET.Element] = []
    for child in case:
        child_name = _xml_local_name(child.tag)
        if child_name in allowed:
            markers.append(child)
            continue
        if any(
            _xml_local_name(descendant.tag) in allowed
            for descendant in child.iter()
            if descendant is not child
        ):
            raise ValueError("JUnit outcome markers must be direct children of testcase")
    return markers


def _case_observation(case: ET.Element, default_class_name: str) -> dict[str, Any]:
    class_name = case.get("classname") or default_class_name
    test_name = case.get("name")
    if not JUNIT_CLASS_PATTERN.fullmatch(class_name):
        raise ValueError(f"JUnit test case has invalid StoryNPCs class name: {class_name!r}")
    if not isinstance(test_name, str) or not test_name.strip() or len(test_name) > 512:
        raise ValueError(f"JUnit test case in {class_name} has an invalid name")

    markers = _case_outcome_markers(case)
    failures = [child for child in markers if _xml_local_name(child.tag) in {"failure", "error"}]
    skipped = any(_xml_local_name(child.tag) == "skipped" for child in markers)
    outcome = "ERROR" if any(_xml_local_name(child.tag) == "error" for child in failures) else (
        "FAILED" if failures else "SKIPPED" if skipped else "PASSED"
    )
    observation: dict[str, Any] = {
        "selector": f"{class_name}#{test_name}",
        "class_name": class_name,
        "test_name": test_name,
        "outcome": outcome,
    }
    if case.get("time") is not None:
        try:
            duration = float(case.get("time", "0"))
            if not math.isfinite(duration) or duration < 0:
                raise ValueError("test duration must be a finite non-negative number")
            observation["duration_seconds"] = duration
        except ValueError:
            observation["duration_seconds"] = None
    if failures:
        detail = "\n".join(
            " ".join(part for part in (failure.get("message"), failure.text) if part)
            for failure in failures
        ).strip()
        observation["failure_detail"] = detail[:MAX_FAILURE_DETAIL]
    return observation


def _validate_junit_case_locations(root: ET.Element, report_name: str) -> None:
    def visit(node: ET.Element, parent_name: str | None) -> None:
        node_name = _xml_local_name(node.tag)
        if node_name == "testcase" and parent_name != "testsuite":
            raise ValueError(
                f"testcase must be a direct child of testsuite in {report_name}"
            )
        for child in node:
            visit(child, node_name)

    visit(root, None)


def _validate_junit_suite_totals(root: ET.Element, report_name: str) -> None:
    for suite in root.iter():
        if _xml_local_name(suite.tag) not in {"testsuite", "testsuites"}:
            continue
        cases = [node for node in suite.iter() if _xml_local_name(node.tag) == "testcase"]
        observed = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
        for case in cases:
            child_names = {_xml_local_name(marker.tag) for marker in _case_outcome_markers(case)}
            for result in ("failures", "errors", "skipped"):
                marker = result[:-1] if result.endswith("s") else result
                if marker in child_names:
                    observed[result] += 1

        mismatches: list[str] = []
        for field, actual in observed.items():
            raw_value = suite.get(field)
            if raw_value is None:
                continue
            try:
                declared = int(raw_value)
            except ValueError as error:
                raise ValueError(
                    f"invalid JUnit suite total {field}={raw_value!r} in {report_name}"
                ) from error
            if declared < 0:
                raise ValueError(f"negative JUnit suite total {field} in {report_name}")
            if declared != actual:
                mismatches.append(f"{field} declares {declared}, observed {actual}")
        if mismatches:
            raise ValueError(
                f"JUnit suite totals disagree in {report_name}: " + "; ".join(mismatches)
            )


def read_junit_cases(
    results_directory: Path,
) -> dict[str, dict[str, Any]]:
    if not results_directory.is_dir():
        raise ValueError(f"JUnit results directory does not exist: {results_directory}")
    reports = sorted(results_directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"no Gradle JUnit XML reports found in {results_directory}")
    if len(reports) > MAX_JUNIT_REPORTS:
        raise ValueError(f"JUnit report count exceeds {MAX_JUNIT_REPORTS}")

    cases: dict[str, dict[str, Any]] = {}
    for report_path in reports:
        if report_path.stat().st_size > MAX_JUNIT_REPORT_BYTES:
            raise ValueError(f"JUnit report exceeds {MAX_JUNIT_REPORT_BYTES} bytes: {report_path.name}")
        try:
            root = ET.parse(report_path).getroot()
        except ET.ParseError as error:
            raise ValueError(f"malformed JUnit report {report_path.name}: {error}") from error

        _validate_junit_case_locations(root, report_path.name)
        _validate_junit_suite_totals(root, report_path.name)
        suites = [node for node in root.iter() if _xml_local_name(node.tag) == "testsuite"]
        if _xml_local_name(root.tag) == "testsuite" and root not in suites:
            suites.insert(0, root)
        for suite in suites:
            default_class_name = suite.get("name", "")
            for case in suite:
                if _xml_local_name(case.tag) != "testcase":
                    continue
                observation = _case_observation(case, default_class_name)
                selector = observation["selector"]
                if selector in cases:
                    raise ValueError(f"duplicate JUnit test case selector: {selector}")
                cases[selector] = observation
                if len(cases) > MAX_JUNIT_CASES:
                    raise ValueError(f"JUnit test case count exceeds {MAX_JUNIT_CASES}")
    if not cases:
        raise ValueError(f"JUnit XML reports contain no test cases: {results_directory}")
    return cases


def build_probe_report(
    catalog: dict[str, Any],
    junit_cases: dict[str, dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, int], list[str]]:
    test_map = catalog.get("storynpcs_test_map", {})
    coverage_map = catalog.get("storynpcs_test_coverage", {})
    blocker_map = catalog.get("storynpcs_test_blockers", {})
    fixtures = catalog.get("fixtures")
    if not isinstance(test_map, dict):
        raise ValueError("storynpcs_test_map must be an object")
    if not isinstance(coverage_map, dict):
        raise ValueError("storynpcs_test_coverage must be an object")
    if not isinstance(blocker_map, dict):
        raise ValueError("storynpcs_test_blockers must be an object")
    if not isinstance(fixtures, list):
        raise ValueError("catalog fixtures must be a list")
    observations: list[dict[str, Any]] = []
    counts = {
        "observed": 0,
        "blocked": 0,
        "blocked_mapped": 0,
        "unmapped": 0,
        "failed": 0,
        "mapped_cases": 0,
        "fixture_count": len(fixtures),
    }
    missing_selectors: list[str] = []
    selector_owners: dict[str, str] = {}

    for fixture in fixtures:
        if not isinstance(fixture, dict) or not isinstance(fixture.get("fixture_id"), str):
            raise ValueError("each catalog fixture must have a string fixture_id")
        fixture_id = fixture["fixture_id"]
        selectors = test_map.get(fixture_id, [])
        if not isinstance(selectors, list) or not all(isinstance(selector, str) for selector in selectors):
            raise ValueError(f"storynpcs_test_map[{fixture_id}] must be a list of JUnit selectors")
        for selector in selectors:
            previous_owner = selector_owners.get(selector)
            if previous_owner is not None and previous_owner != fixture_id:
                raise ValueError(
                    f"JUnit selector {selector} is assigned to multiple fixtures: "
                    f"{previous_owner}, {fixture_id}"
                )
            if previous_owner == fixture_id:
                raise ValueError(f"storynpcs_test_map[{fixture_id}] contains duplicate JUnit selectors")
            selector_owners[selector] = fixture_id
        coverage = coverage_map.get(fixture_id)
        if selectors and (
            not isinstance(coverage, dict)
            or not isinstance(coverage.get("observed_scope"), str)
            or not isinstance(coverage.get("uncovered_scope"), str)
        ):
            raise ValueError(f"mapped fixture {fixture_id} must declare observed and uncovered scope")
        counts["mapped_cases"] += len(selectors)
        found = [junit_cases[selector] for selector in selectors if selector in junit_cases]
        missing = [selector for selector in selectors if selector not in junit_cases]
        if missing:
            missing_selectors.extend(missing)

        if not selectors:
            blocker = blocker_map.get(fixture_id)
            if not isinstance(blocker, dict):
                raise ValueError(f"empty-selector fixture {fixture_id} must have a declared blocker")
            owner_issue_ids = blocker.get("owner_issue_ids")
            reason = blocker.get("reason")
            if (
                not isinstance(owner_issue_ids, list)
                or not owner_issue_ids
                or not all(isinstance(owner, str) and owner.strip() for owner in owner_issue_ids)
                or not isinstance(reason, str)
                or not reason.strip()
            ):
                raise ValueError(f"storynpcs_test_blockers[{fixture_id}] is incomplete")
            probe: dict[str, Any] = {
                "status": "BLOCKED",
                "reason": reason,
                "owner_issue_ids": owner_issue_ids,
                "next_evidence": (
                    "Owning issue(s) " + ", ".join(owner_issue_ids)
                    + " must implement the behavior and add a focused JUnit case before its exact selector is mapped"
                ),
            }
            counts["blocked"] += 1
            counts["unmapped"] += 1
        elif missing:
            probe = {
                "status": "BLOCKED",
                "reason": "Mapped JUnit cases were absent from the latest full test run",
                "next_evidence": "Restore or correct the fixture-to-JUnit selector mapping and rerun the complete Gradle test suite",
                "missing_selectors": missing,
            }
            counts["blocked"] += 1
            counts["blocked_mapped"] += 1
        elif any(case["outcome"] == "SKIPPED" for case in found):
            probe = {
                "status": "BLOCKED",
                "reason": "One or more mapped JUnit cases were skipped instead of executed",
                "next_evidence": "Enable the mapped test cases in the standard Gradle test run",
                "tests": found,
            }
            counts["blocked"] += 1
            counts["blocked_mapped"] += 1
        else:
            outcome = "FAILED" if any(case["outcome"] in {"FAILED", "ERROR"} for case in found) else "PASSED"
            probe = {
                "status": "OBSERVED",
                "result": {
                    "runner": "Gradle JUnit XML",
                    "execution_scope": "StoryNPCs JVM tests only; not a live Minecraft or CustomNPCs runtime",
                    "observed_scope": coverage["observed_scope"],
                    "uncovered_scope": coverage["uncovered_scope"],
                    "claim_limit": "Only the listed JUnit case outcomes are reported; they do not certify the fixture's full expected result or unexecuted layers.",
                    "outcome": outcome,
                    "tests": found,
                },
            }
            counts["observed"] += 1
            if outcome != "PASSED":
                counts["failed"] += 1

        observations.append({
            "fixture_id": fixture_id,
            "storynpcs_probe": probe,
            # Target runtime evidence remains independently required for parity.
            "evidence_state": "UNVERIFIED_TARGET_RUNTIME",
        })

    return {"fixtures": observations}, counts, missing_selectors


def fixture_run_passed(
    gradle_exit_code: int,
    fixture_report: dict[str, Any],
    counts: dict[str, int],
    missing_selectors: list[str],
) -> bool:
    return (
        gradle_exit_code == 0
        and fixture_report.get("status") == "PASS"
        and counts.get("failed", 0) == 0
        and counts.get("blocked", 0) == 0
        and counts.get("blocked_mapped", 0) == 0
        and counts.get("unmapped", 0) == 0
        and counts.get("observed", 0) == counts.get("fixture_count", -1)
        and counts.get("mapped_cases", 0) > 0
        and not missing_selectors
    )


def fixture_run_ci_check_passed(
    gradle_exit_code: int,
    fixture_report: dict[str, Any],
    probe_report: dict[str, Any],
    counts: dict[str, int],
    missing_selectors: list[str],
    catalog: dict[str, Any],
) -> bool:
    """Accept mapped JVM test execution while preserving declared parity blockers.

    This is intentionally weaker than fixture_run_passed: it is only suitable
    for CI's test-execution gate and cannot certify complete fixture coverage or
    target-runtime parity.
    """
    try:
        fixtures = catalog["fixtures"]
        test_map = catalog["storynpcs_test_map"]
        blocker_map = catalog["storynpcs_test_blockers"]
        fixture_ids = [fixture["fixture_id"] for fixture in fixtures]
        if len(fixture_ids) != len(set(fixture_ids)):
            return False
        expected_blocker_ids = {
            fixture_id for fixture_id in fixture_ids if not test_map.get(fixture_id)
        }
        if set(blocker_map) != expected_blocker_ids:
            return False
        for fixture_id in expected_blocker_ids:
            blocker = blocker_map[fixture_id]
            if (
                not isinstance(blocker, dict)
                or not isinstance(blocker.get("reason"), str)
                or not blocker["reason"].strip()
                or not isinstance(blocker.get("owner_issue_ids"), list)
                or not blocker["owner_issue_ids"]
                or not all(isinstance(owner, str) and owner.strip() for owner in blocker["owner_issue_ids"])
            ):
                return False

        coverage = fixture_report["coverage"]
        evidence = fixture_report["evidence"]
        unmapped_ids = coverage["unmapped_junit_fixture_ids"]
        reported_blockers = coverage["unmapped_junit_fixture_blockers"]
        source_status = fixture_report["source_provenance_status"]
        expected_provenance_blockers = (
            [
                "--jar was not supplied; target source provenance was not checked",
                "--research-root was not supplied; target source provenance was not checked",
                "--decompiled-root was not supplied; target source provenance was not checked",
            ]
            if source_status == "UNVERIFIED"
            else []
        )
        actual_probes = probe_report["fixtures"]
        probes_by_id = {item["fixture_id"]: item["storynpcs_probe"] for item in actual_probes}
        if len(probes_by_id) != len(actual_probes) or set(probes_by_id) != set(fixture_ids):
            return False

        for fixture_id in fixture_ids:
            probe = probes_by_id[fixture_id]
            if fixture_id in expected_blocker_ids:
                blocker = blocker_map[fixture_id]
                if (
                    probe.get("status") != "BLOCKED"
                    or probe.get("owner_issue_ids") != blocker["owner_issue_ids"]
                    or probe.get("reason") != blocker["reason"]
                ):
                    return False
            else:
                result = probe.get("result")
                observed_selectors = (
                    [case.get("selector") for case in result.get("tests", [])]
                    if isinstance(result, dict) and isinstance(result.get("tests"), list)
                    else []
                )
                if (
                    probe.get("status") != "OBSERVED"
                    or not isinstance(result, dict)
                    or result.get("outcome") != "PASSED"
                    or not isinstance(result.get("tests"), list)
                    or not result["tests"]
                    or observed_selectors != test_map.get(fixture_id)
                    or any(case.get("outcome") != "PASSED" for case in result["tests"])
                ):
                    return False

        expected_status = "BLOCKED" if source_status == "UNVERIFIED" else "PASS"
        expected_blocker_rows = [
            {
                "fixture_id": fixture_id,
                "owner_issue_ids": blocker_map[fixture_id]["owner_issue_ids"],
                "reason": blocker_map[fixture_id]["reason"],
            }
            for fixture_id in sorted(expected_blocker_ids)
        ]
        return (
            gradle_exit_code == 0
            and not missing_selectors
            and counts.get("failed") == 0
            and counts.get("blocked_mapped") == 0
            and counts.get("unmapped") == len(expected_blocker_ids)
            and counts.get("blocked") == len(expected_blocker_ids)
            and counts.get("observed") == len(fixture_ids) - len(expected_blocker_ids)
            and counts.get("fixture_count") == len(fixture_ids)
            and counts.get("mapped_cases", 0) > 0
            and fixture_report.get("status") == expected_status
            and fixture_report.get("validation_status") == "PASS"
            and source_status in {"VERIFIED", "UNVERIFIED"}
            and fixture_report.get("provenance_blockers") == expected_provenance_blockers
            and fixture_report.get("catalog_errors") == []
            and fixture_report.get("storynpcs_execution_coverage") == (
                "INCOMPLETE" if expected_blocker_ids else "MAPPED"
            )
            and coverage.get("fixture_count") == len(fixture_ids)
            and coverage.get("mapped_junit_fixture_count") == len(fixture_ids) - len(expected_blocker_ids)
            and unmapped_ids == sorted(expected_blocker_ids)
            and reported_blockers == expected_blocker_rows
            and evidence.get("status") == "PASS"
            and evidence.get("errors") == []
            and evidence.get("parity_status") == "BLOCKED"
            and evidence.get("certification_eligible") is False
        )
    except (KeyError, TypeError, AttributeError):
        return False


def gradle_test_command() -> str:
    if os.name == "nt":
        # The command is a fixed constant; shell=True is required for cmd.exe to run .bat.
        return ".\\gradlew.bat cleanTest test --rerun-tasks --console=plain"
    return "./gradlew cleanTest test --rerun-tasks --console=plain"


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")


def _invalidate_previous_reports(probe_report_path: Path, report_path: Path) -> None:
    paths = [Path(os.path.abspath(probe_report_path)), Path(os.path.abspath(report_path))]
    try:
        resolved_paths = [path.resolve(strict=False) for path in paths]
    except (OSError, RuntimeError) as error:
        raise ValueError(f"could not safely resolve report output paths: {error}") from error
    if os.path.normcase(str(resolved_paths[0])) == os.path.normcase(str(resolved_paths[1])):
        raise ValueError("--probe-report and --report must resolve to different files")
    try:
        if os.path.samefile(paths[0], paths[1]):
            raise ValueError("--probe-report and --report must resolve to different files")
    except FileNotFoundError:
        pass

    # Unlink the user-specified lexical paths (not their symlink targets), with
    # the authoritative report first so auxiliary cleanup failure cannot leave
    # an old PASS report at the primary output path.
    paths[1].unlink(missing_ok=True)
    paths[0].unlink(missing_ok=True)


def _write_failure_reports(
    probe_report_path: Path,
    report_path: Path,
    command: str,
    gradle_exit_code: int | None,
    run_state: str,
    reason: str,
) -> None:
    probe_report = {"run_state": run_state, "fixtures": []}
    report = {
        "status": "FAIL",
        "gradle_test_command": command,
        "gradle_exit_code": gradle_exit_code,
        "evidence_ingested": False,
        "error": reason,
        "storynpcs_probe_report": probe_report,
        "fixture_report": None,
    }
    # Replace the authoritative report first so an auxiliary write failure cannot
    # leave an old PASS at the primary output path.
    _write_json(report_path, report)
    _write_json(probe_report_path, probe_report)


def parse_args() -> argparse.Namespace:
    repository_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=repository_root / "docs" / "parity" / "fixture-catalog.json",
    )
    parser.add_argument(
        "--probe-report",
        type=Path,
        default=repository_root / "build" / "reports" / "parity" / "storynpcs-probe-report.json",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=repository_root / "build" / "reports" / "parity" / "fixture-report.json",
    )
    parser.add_argument("--jar", type=Path, help="exact target CustomNPCs JAR used for source verification")
    parser.add_argument("--research-root", type=Path, help="root containing the hashed research inventories")
    parser.add_argument("--decompiled-root", type=Path, help="root containing source decompiled from the target JAR")
    parser.add_argument(
        "--ci-allow-declared-blockers",
        action="store_true",
        help=(
            "return success only when mapped JVM tests pass and all remaining empty selectors "
            "are exactly declared feature blockers; parity remains BLOCKED"
        ),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repository_root = Path(__file__).resolve().parents[2]
    command = gradle_test_command()
    try:
        # These files are generated run artifacts; remove old evidence before any new work.
        _invalidate_previous_reports(args.probe_report, args.report)
    except (OSError, ValueError) as error:
        reason = f"Could not prepare fresh fixture report outputs; Gradle was not started: {error}"
        print(reason, file=sys.stderr)
        if isinstance(error, OSError):
            try:
                _write_failure_reports(
                    args.probe_report, args.report, command, None,
                    "OUTPUT_PREFLIGHT_FAILED", reason,
                )
            except OSError as write_error:
                print(f"Could not write preflight failure report: {write_error}", file=sys.stderr)
        return 1
    print(f"Running full Java test suite: {command}")
    try:
        gradle_result = subprocess.run(command, cwd=repository_root, shell=True, check=False)
    except OSError as error:
        reason = f"Could not start Gradle; no JUnit evidence was accepted: {error}"
        print(reason, file=sys.stderr)
        try:
            _write_failure_reports(
                args.probe_report, args.report, command, None, "GRADLE_LAUNCH_FAILED", reason
            )
        except OSError as write_error:
            print(f"Could not write Gradle failure reports: {write_error}", file=sys.stderr)
        return 1

    if gradle_result.returncode != 0:
        reason = "Gradle exited non-zero; no JUnit evidence was accepted."
        try:
            _write_failure_reports(
                args.probe_report, args.report, command, gradle_result.returncode,
                "GRADLE_FAILED", reason,
            )
        except OSError as error:
            print(f"Could not write Gradle failure reports: {error}", file=sys.stderr)
        print(reason, file=sys.stderr)
        return 1

    try:
        catalog = load_catalog(args.catalog)
        junit_cases = read_junit_cases(repository_root / "build" / "test-results" / "test")
        probe_report, counts, missing_selectors = build_probe_report(catalog, junit_cases)
        _write_json(args.probe_report, probe_report)
        fixture_report = run_catalog(
            args.catalog,
            args.probe_report,
            jar_path=args.jar,
            research_root=args.research_root,
            decompiled_root=args.decompiled_root,
        )
    except (OSError, TypeError, ValueError, KeyError, ET.ParseError) as error:
        reason = f"StoryNPCs fixture adapter failed; no JUnit evidence was accepted: {error}"
        print(reason, file=sys.stderr)
        try:
            _write_failure_reports(
                args.probe_report, args.report, command, gradle_result.returncode,
                "ADAPTER_FAILED", reason,
            )
        except OSError as write_error:
            print(f"Could not write adapter failure reports: {write_error}", file=sys.stderr)
        return 1

    test_suite_count = len({case["class_name"] for case in junit_cases.values()})
    strict_pass = fixture_run_passed(
        gradle_result.returncode, fixture_report, counts, missing_selectors
    )
    ci_pass = bool(getattr(args, "ci_allow_declared_blockers", False)) and fixture_run_ci_check_passed(
        gradle_result.returncode,
        fixture_report,
        probe_report,
        counts,
        missing_selectors,
        catalog,
    )
    report_status = "PASS" if strict_pass else "BLOCKED" if ci_pass else "FAIL"
    report = {
        "status": report_status,
        "ci_validation_status": (
            "PASS" if ci_pass else "FAIL"
        ) if getattr(args, "ci_allow_declared_blockers", False) else "NOT_REQUESTED",
        "ci_allowed_blockers": sorted(catalog.get("storynpcs_test_blockers", {})) if ci_pass else [],
        "parity_status": fixture_report["evidence"].get("parity_status", "UNKNOWN"),
        "gradle_test_command": command,
        "gradle_exit_code": gradle_result.returncode,
        "junit_test_case_count": len(junit_cases),
        "junit_test_suite_count": test_suite_count,
        "storynpcs_fixture_counts": counts,
        "missing_test_selectors": missing_selectors,
        "storynpcs_probe_report": probe_report,
        "fixture_report": fixture_report,
    }
    try:
        _write_json(args.report, report)
    except OSError as error:
        print(f"Could not write fixture reports: {error}", file=sys.stderr)
        return 1

    print(
        f"Gradle JUnit results: {len(junit_cases)} test cases across {test_suite_count} suites. "
        "StoryNPCs probes: "
        f"{counts['observed']} observed, {counts['blocked']} blocked, {counts['failed']} failed; "
        f"target parity={fixture_report['evidence'].get('parity_status', 'UNKNOWN')}"
    )
    if ci_pass and not strict_pass:
        print("CI validation PASS: mapped JVM test execution passed; declared feature blockers remain, and target parity is still BLOCKED.")
    print(f"Detailed evidence: {args.report}")
    print(f"Fixture-ID probe report: {args.probe_report}")
    return 0 if strict_pass or ci_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
