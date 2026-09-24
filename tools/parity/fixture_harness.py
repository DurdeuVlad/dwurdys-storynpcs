#!/usr/bin/env python3
"""Validate and report the executable CustomNPCs parity fixture catalog."""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Any

try:
    from tools.parity.evidence_gate import evaluate_fixtures
    from tools.parity.generate_target_surface_manifest import (
        OPERATION_CLOSING_ISSUES,
        ROLE_ISSUES,
        TARGET_NAME,
        TARGET_SHA1,
        TARGET_SHA256,
        jar_inventory,
        validate_manifest,
        verify_source_provenance,
    )
except ModuleNotFoundError:  # Direct execution: python tools/parity/fixture_harness.py
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.parity.evidence_gate import evaluate_fixtures
    from tools.parity.generate_target_surface_manifest import (
        OPERATION_CLOSING_ISSUES,
        ROLE_ISSUES,
        TARGET_NAME,
        TARGET_SHA1,
        TARGET_SHA256,
        jar_inventory,
        validate_manifest,
        verify_source_provenance,
    )


OPERATION_FAMILIES = {
    "NPC-MODULE-MUTATION",
    "DIALOG-DEFINITION",
    "DIALOG-PLAYER-CHOICE",
    "FACTION-PROGRESSION",
    "QUEST-DEFINITION",
    "QUEST-COMPLETION",
    "ROLE-JOB",
    "INVENTORY-EQUIPMENT",
    "TRADE",
    "BANK",
    "TRANSPORT",
    "SPAWN-CLONE",
    "MARK",
    "SCRIPT",
    "CONFIG-WORLD-TOOLS",
}

DOMAINS = {
    "core-entity-and-variants",
    "display-and-aesthetics",
    "stats-and-combat",
    "ai-and-movement",
    "targeting-and-defeat",
    "inventory-equipment-drops",
    "dialogue",
    "quests",
    "factions",
    "roles",
    "jobs",
    "transport",
    "banks",
    "trading",
    "companions",
    "spawners-templates",
    "marks",
    "creator-world-tools",
    "scripting",
    "commands",
    "networking",
    "persistence",
}

LAYERS = {"server", "client", "storynpcs-runtime", "persistence"}
TARGET_PROBE_POLICIES = {"optional", "parity-only"}
FIXTURE_ID_PATTERN = re.compile(r"^P\d+-\d+\.[A-Za-z0-9][A-Za-z0-9._-]*$")
JUNIT_SELECTOR_PATTERN = re.compile(
    r"^com\.storynpcs(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+#([^\r\n#]{1,256})$"
)
BASELINE_EVIDENCE_TEMPLATE = {
    "target_probe": {
        "status": "UNAVAILABLE",
        "reason": "P0-4 catalog has no launchable target runtime probe yet",
        "next_evidence": "Run the fixture against the exact supplied CustomNPCs JAR runtime and record the observed result",
    },
    "storynpcs_probe": {"status": "NOT_RUN"},
    "comparison": {"rule": "target-runtime-required", "outcome": "NOT_COMPARABLE"},
    "evidence_state": "UNVERIFIED_TARGET_RUNTIME",
}


def load_catalog(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("fixture catalog must be an object")
    return value


def validate_catalog(
    catalog: dict[str, Any],
    *,
    target_symbols: set[str] | None = None,
    registered_issue_ids: set[str] | None = None,
) -> list[str]:
    errors: list[str] = []
    if catalog.get("schema_version") != 2:
        errors.append("schema_version must be 2")
    if set(catalog.get("required_operation_families", [])) != OPERATION_FAMILIES:
        errors.append("required operation family set does not match the 15-operation contract")
    if set(catalog.get("required_domains", [])) != DOMAINS:
        errors.append("required domain set does not match the 22-domain contract")
    if catalog.get("evidence_template") != BASELINE_EVIDENCE_TEMPLATE:
        errors.append("evidence_template differs from the immutable blocked baseline")

    fixtures = catalog.get("fixtures")
    if not isinstance(fixtures, list) or not fixtures:
        return errors + ["fixtures must be a non-empty list"]
    seen_ids: set[str] = set()
    issue_ids_by_fixture: dict[str, set[str]] = {}
    selector_owners: dict[str, str] = {}
    covered_operations: set[str] = set()
    covered_domains: set[str] = set()
    for index, fixture in enumerate(fixtures):
        prefix = f"fixtures[{index}]"
        if not isinstance(fixture, dict):
            errors.append(f"{prefix} must be an object")
            continue
        fixture_id = fixture.get("fixture_id")
        if not isinstance(fixture_id, str) or not fixture_id.strip():
            errors.append(f"{prefix}.fixture_id must be non-empty")
        elif not FIXTURE_ID_PATTERN.fullmatch(fixture_id):
            errors.append(f"{prefix}.fixture_id must match P<priority>-<number>.<name>")
        elif fixture_id in seen_ids:
            errors.append(f"duplicate fixture_id: {fixture_id}")
        else:
            seen_ids.add(fixture_id)
        issue_ids = fixture.get("issue_ids")
        if not isinstance(issue_ids, list) or not issue_ids:
            errors.append(f"{prefix}.issue_ids must be non-empty")
        else:
            if isinstance(fixture_id, str):
                issue_ids_by_fixture[fixture_id] = {
                    issue_id for issue_id in issue_ids if isinstance(issue_id, str)
                }
            if registered_issue_ids is not None:
                for issue_id in issue_ids:
                    if not isinstance(issue_id, str) or issue_id not in registered_issue_ids:
                        errors.append(f"{prefix}.issue_ids references unknown issue: {issue_id}")
        operation = fixture.get("operation_family")
        if operation not in OPERATION_FAMILIES:
            errors.append(f"{prefix}.operation_family is unknown: {operation}")
        else:
            covered_operations.add(operation)
        domain = fixture.get("domain")
        if domain not in DOMAINS:
            errors.append(f"{prefix}.domain is unknown: {domain}")
        else:
            covered_domains.add(domain)
        layers = fixture.get("required_layers")
        if (
            not isinstance(layers, list)
            or not layers
            or not all(isinstance(layer, str) for layer in layers)
            or not set(layers).issubset(LAYERS)
        ):
            errors.append(f"{prefix}.required_layers must contain known non-empty layers")
        if isinstance(layers, list) and "target-runtime" in layers:
            errors.append(f"{prefix}.required_layers must not require target-runtime; target access gates parity evidence only")
        if isinstance(layers, list) and "storynpcs-runtime" not in layers:
            errors.append(f"{prefix}.required_layers must include storynpcs-runtime")
        if fixture.get("target_probe_policy") not in TARGET_PROBE_POLICIES:
            errors.append(f"{prefix}.target_probe_policy must be optional or parity-only")
        if not isinstance(fixture.get("target_symbol"), str) or not fixture["target_symbol"].strip():
            errors.append(f"{prefix}.target_symbol must be non-empty")
        elif target_symbols is not None and fixture["target_symbol"] not in target_symbols:
            errors.append(f"{prefix}.target_symbol is absent from the verified target manifest: "
                          f"{fixture['target_symbol']}")
        if "input" not in fixture or "expected_result" not in fixture:
            errors.append(f"{prefix} must declare input and expected_result")

    missing_operations = OPERATION_FAMILIES - covered_operations
    missing_domains = DOMAINS - covered_domains
    if missing_operations:
        errors.append(f"uncovered operation families: {sorted(missing_operations)}")
    if missing_domains:
        errors.append(f"uncovered domains: {sorted(missing_domains)}")

    test_map = catalog.get("storynpcs_test_map")
    if not isinstance(test_map, dict):
        errors.append("storynpcs_test_map must map every fixture ID to a JUnit case list")
    else:
        missing_test_map = sorted(seen_ids - set(test_map))
        unknown_test_map = sorted(set(test_map) - seen_ids)
        if missing_test_map:
            errors.append(f"storynpcs_test_map is missing fixture IDs: {missing_test_map}")
        if unknown_test_map:
            errors.append(f"storynpcs_test_map contains unknown fixture IDs: {unknown_test_map}")
        for fixture_id, selectors in test_map.items():
            if not isinstance(selectors, list) or not all(isinstance(selector, str) for selector in selectors):
                errors.append(f"storynpcs_test_map[{fixture_id}] must be a list of JUnit selectors")
                continue
            if len(selectors) != len(set(selectors)):
                errors.append(f"storynpcs_test_map[{fixture_id}] contains duplicate JUnit selectors")
            for selector in selectors:
                if not JUNIT_SELECTOR_PATTERN.fullmatch(selector):
                    errors.append(
                        f"storynpcs_test_map[{fixture_id}] has invalid JUnit selector: {selector}"
                    )
                previous_owner = selector_owners.get(selector)
                if previous_owner is not None and previous_owner != fixture_id:
                    errors.append(
                        f"JUnit selector {selector} is assigned to multiple fixtures: "
                        f"{previous_owner}, {fixture_id}"
                    )
                else:
                    selector_owners[selector] = fixture_id
        empty_selector_fixture_ids = {
            fixture_id for fixture_id, selectors in test_map.items()
            if isinstance(selectors, list) and not selectors and fixture_id in seen_ids
        }
        blocker_map = catalog.get("storynpcs_test_blockers")
        if not isinstance(blocker_map, dict):
            errors.append("storynpcs_test_blockers must describe every empty-selector fixture")
        else:
            missing_blockers = sorted(empty_selector_fixture_ids - set(blocker_map))
            unexpected_blockers = sorted(set(blocker_map) - empty_selector_fixture_ids)
            if missing_blockers or unexpected_blockers:
                errors.append(
                    "storynpcs_test_blockers keys must match empty-selector fixture IDs: "
                    f"missing={missing_blockers}, unexpected={unexpected_blockers}"
                )
            for fixture_id, blocker in blocker_map.items():
                if not isinstance(blocker, dict):
                    errors.append(f"storynpcs_test_blockers[{fixture_id}] must be an object")
                    continue
                reason = blocker.get("reason")
                if not isinstance(reason, str) or not reason.strip():
                    errors.append(f"storynpcs_test_blockers[{fixture_id}].reason must be non-empty")
                owners = blocker.get("owner_issue_ids")
                if (
                    not isinstance(owners, list)
                    or not owners
                    or not all(isinstance(owner, str) and owner.strip() for owner in owners)
                    or len(owners) != len(set(owners))
                ):
                    errors.append(
                        f"storynpcs_test_blockers[{fixture_id}].owner_issue_ids must be unique non-empty issue IDs"
                    )
                elif not set(owners).issubset(issue_ids_by_fixture.get(fixture_id, set())):
                    errors.append(
                        f"storynpcs_test_blockers[{fixture_id}].owner_issue_ids must belong to its fixture"
                    )
        coverage_map = catalog.get("storynpcs_test_coverage")
        if not isinstance(coverage_map, dict):
            errors.append("storynpcs_test_coverage must describe the exact scope for mapped fixtures")
        else:
            mapped_fixture_ids = {
                fixture_id for fixture_id, selectors in test_map.items()
                if isinstance(selectors, list) and selectors
            }
            if set(coverage_map) != mapped_fixture_ids:
                errors.append("storynpcs_test_coverage keys must match fixtures with mapped JUnit cases")
            for fixture_id, coverage in coverage_map.items():
                if not isinstance(coverage, dict):
                    errors.append(f"storynpcs_test_coverage[{fixture_id}] must be an object")
                    continue
                for field in ("observed_scope", "uncovered_scope"):
                    if not isinstance(coverage.get(field), str) or not coverage[field].strip():
                        errors.append(
                            f"storynpcs_test_coverage[{fixture_id}].{field} must be non-empty"
                        )
    return errors


def validate_manifest_fixture_coverage(
    manifest: dict[str, Any], catalog: dict[str, Any]
) -> list[str]:
    errors: list[str] = []
    if not isinstance(manifest, dict):
        return ["target surface manifest must be an object"]
    if not isinstance(catalog, dict):
        return ["fixture catalog must be an object"]
    fixtures = catalog.get("fixtures", [])
    fixture_ids = {
        fixture.get("fixture_id") for fixture in fixtures if isinstance(fixture, dict)
    }
    domains = {
        domain: sorted(
            fixture["fixture_id"] for fixture in fixtures if fixture.get("domain") == domain
        )
        for domain in catalog.get("required_domains", [])
    }
    operations = {
        operation: sorted(
            fixture["fixture_id"]
            for fixture in fixtures
            if fixture.get("operation_family") == operation
        )
        for operation in catalog.get("required_operation_families", [])
    }
    expected_crosswalk = {"domains": domains, "operation_families": operations}
    if manifest.get("fixture_coverage") != expected_crosswalk:
        errors.append("manifest fixture_coverage differs from the runnable fixture catalog")
    supplied_crosswalk = manifest.get("fixture_coverage", {})
    if isinstance(supplied_crosswalk, dict):
        for category, mapping in supplied_crosswalk.items():
            if not isinstance(mapping, dict):
                errors.append(f"fixture_coverage.{category} must be an object")
                continue
            for key, refs in mapping.items():
                if (
                    not isinstance(refs, list)
                    or not all(isinstance(ref, str) for ref in refs)
                    or not set(refs).issubset(fixture_ids)
                ):
                    errors.append(f"fixture_coverage.{category}[{key}] references an unknown fixture")

    issue_to_fixtures: dict[str, set[str]] = {}
    for fixture in fixtures:
        for issue_id in fixture.get("issue_ids", []):
            issue_to_fixtures.setdefault(issue_id, set()).add(fixture["fixture_id"])

    surfaces = manifest.get("surfaces", {})
    if not isinstance(surfaces, dict):
        return errors + ["manifest surfaces must be an object"]
    seen_inventory_ids: set[str] = set()
    for surface, rows in surfaces.items():
        if not isinstance(rows, list):
            errors.append(f"manifest surface {surface} must be a list")
            continue
        for row in rows:
            if not isinstance(row, dict):
                errors.append(f"manifest surface {surface} contains a non-object row")
                continue
            inventory_id = row.get("inventory_id")
            if "fixture_id" in row:
                errors.append(f"manifest {surface} row uses fixture_id instead of inventory_id")
            if not isinstance(inventory_id, str) or not inventory_id:
                errors.append(f"manifest {surface} row is missing inventory_id")
            elif inventory_id in seen_inventory_ids:
                errors.append(f"duplicate manifest inventory_id: {inventory_id}")
            else:
                seen_inventory_ids.add(inventory_id)
            refs = row.get("parity_fixture_ids")
            if (
                not isinstance(refs, list)
                or not all(isinstance(ref, str) for ref in refs)
                or len(refs) != len(set(refs))
            ):
                errors.append(f"manifest {surface}/{row.get('symbol')} has invalid parity_fixture_ids")
                continue
            if not set(refs).issubset(fixture_ids):
                errors.append(f"manifest {surface}/{row.get('symbol')} references an unknown fixture")
            if row.get("coverage_status") != ("FIXTURE_REFERENCED" if refs else "INVENTORY_ONLY"):
                errors.append(f"manifest {surface}/{row.get('symbol')} has inconsistent coverage_status")

            if surface == "operation_matrix":
                operation = row.get("id")
                expected_refs = operations.get(operation, [])
                if refs != expected_refs:
                    errors.append(f"operation {operation} has incorrect fixture references")
                if row.get("closing_issue_ids") != OPERATION_CLOSING_ISSUES.get(operation):
                    errors.append(f"operation {operation} has incorrect closing issue IDs")
                row_issue_ids = row.get("issue_ids", [])
                if not isinstance(row_issue_ids, list) or "P0-4" not in row_issue_ids:
                    errors.append(f"operation {operation} omits the P0-4 harness owner")
            elif surface == "roles":
                expected_issue_ids = ROLE_ISSUES.get(row.get("symbol"))
                if expected_issue_ids is None or row.get("issue_ids") != expected_issue_ids:
                    errors.append(f"role {row.get('symbol')} has incorrect issue mapping")
                expected_refs = sorted(
                    set().union(
                        *(issue_to_fixtures.get(issue, set()) for issue in row.get("issue_ids", []))
                    )
                )
                if refs != expected_refs:
                    errors.append(f"role {row.get('symbol')} has incorrect fixture references")
            elif row.get("domain") in domains:
                if refs != domains[row["domain"]]:
                    errors.append(f"{surface}/{row.get('symbol')} has incorrect domain fixture references")
            else:
                expected_refs = sorted(
                    set().union(
                        *(issue_to_fixtures.get(issue, set()) for issue in row.get("issue_ids", []))
                    )
                )
                if refs != expected_refs:
                    errors.append(f"{surface}/{row.get('symbol')} has incorrect issue fixture references")
    return errors


def _probe_report_entries(probe_report: dict[str, Any] | None) -> tuple[dict[str, dict[str, Any]], list[str]]:
    if probe_report is None:
        return {}, []
    if not isinstance(probe_report, dict):
        return {}, ["probe report must be an object"]
    entries = probe_report.get("fixtures")
    if not isinstance(entries, list):
        return {}, ["probe report fixtures must be a list"]
    indexed: dict[str, dict[str, Any]] = {}
    errors: list[str] = []
    for index, entry in enumerate(entries):
        prefix = f"probe_report.fixtures[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{prefix} must be an object")
            continue
        fixture_id = entry.get("fixture_id")
        if not isinstance(fixture_id, str) or not fixture_id.strip():
            errors.append(f"{prefix}.fixture_id must be a non-empty string")
            continue
        if fixture_id in indexed:
            errors.append(f"probe report contains duplicate fixture_id: {fixture_id}")
            continue
        indexed[fixture_id] = entry
    return indexed, errors


def expand_fixtures(
    catalog: dict[str, Any],
    probe_report: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    template = deepcopy(BASELINE_EVIDENCE_TEMPLATE)
    probe_entries, probe_errors = _probe_report_entries(probe_report)
    if probe_errors:
        raise ValueError("; ".join(probe_errors))
    catalog_ids = {entry["fixture_id"] for entry in catalog["fixtures"]}
    unknown_probe_ids = sorted(set(probe_entries) - catalog_ids)
    if unknown_probe_ids:
        raise ValueError("probe report references unknown fixtures: " + ", ".join(unknown_probe_ids))
    fixtures: list[dict[str, Any]] = []
    for entry in catalog["fixtures"]:
        probe = probe_entries.get(entry["fixture_id"], {})
        target_probe = dict(template.get("target_probe", {}))
        storynpcs_probe = dict(template.get("storynpcs_probe", {}))
        comparison = dict(template.get("comparison", {}))
        # Probe JSON is caller-controlled input, not proof of a target runtime.
        # Until a provenance-verifying target adapter exists, imports may add
        # StoryNPCs observations only; target and comparison state stay blocked.
        if isinstance(probe.get("storynpcs_probe"), dict):
            storynpcs_probe.update(deepcopy(probe["storynpcs_probe"]))
        evidence_state = template["evidence_state"]
        actual_result = probe.get("actual_result")
        if actual_result is None and storynpcs_probe.get("status") == "OBSERVED":
            actual_result = storynpcs_probe.get("result")
        fixture = {
            "fixture_id": entry["fixture_id"],
            "issue_ids": entry["issue_ids"],
            "target_symbol": entry["target_symbol"],
            "operation_family": entry["operation_family"],
            "setup": {"domain": entry["domain"], "input": entry["input"]},
            "required_layers": entry["required_layers"],
            "target_probe_policy": entry["target_probe_policy"],
            "target_probe": target_probe,
            "storynpcs_probe": storynpcs_probe,
            "comparison": comparison,
            "evidence_state": evidence_state,
            "failure_context": {
                "issue_ids": entry["issue_ids"],
                "target_symbol": entry["target_symbol"],
                "input": entry["input"],
                "expected_result": entry["expected_result"],
                "actual_result": actual_result,
                "evidence_state": evidence_state,
            },
        }
        fixtures.append(fixture)
    return fixtures


def run_catalog(
    path: Path,
    probe_report_path: Path | None = None,
    *,
    jar_path: Path | None = None,
    research_root: Path | None = None,
    decompiled_root: Path | None = None,
) -> dict[str, Any]:
    catalog = load_catalog(path)
    repository_root = path.resolve().parents[2]
    manifest_path = repository_root / "docs" / "parity" / "target-surface-manifest.json"
    issue_register_path = repository_root / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md"
    issue_surface_claims_path = repository_root / "docs" / "parity" / "issue-surface-claims.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("target surface manifest must be an object")
        issue_surface_claims = json.loads(issue_surface_claims_path.read_text(encoding="utf-8"))
        if not isinstance(issue_surface_claims, dict):
            raise ValueError("issue surface claims must be an object")
        surfaces = manifest.get("surfaces")
        class_rows = surfaces.get("target_classes", []) if isinstance(surfaces, dict) else []
        target_symbols = {
            row["symbol"] for row in class_rows
            if isinstance(row, dict) and isinstance(row.get("symbol"), str)
        }
        issue_ids = set(re.findall(r"^###\s+(P\d+-\d+)\s+—", issue_register_path.read_text(encoding="utf-8"), re.MULTILINE))
    except (OSError, TypeError, ValueError, KeyError) as error:
        return {
            "status": "FAIL",
            "catalog_errors": [f"could not load semantic parity references: {error}"],
            "coverage": {},
            "evidence": {"status": "FAIL", "parity_status": "BLOCKED"},
        }
    catalog_errors = validate_catalog(
        catalog,
        target_symbols=target_symbols,
        registered_issue_ids=issue_ids,
    )
    if not catalog_errors:
        fixture_ids = {fixture["fixture_id"] for fixture in catalog["fixtures"]}
        try:
            validate_manifest(manifest, issue_ids, issue_surface_claims, fixture_ids)
        except (KeyError, TypeError, ValueError) as error:
            catalog_errors.append(f"target surface manifest failed full schema validation: {error}")
    catalog_errors.extend(validate_manifest_fixture_coverage(manifest, catalog))
    if catalog_errors:
        return {
            "status": "FAIL",
            "validation_status": "FAIL",
            "source_provenance_status": "UNVERIFIED",
            "provenance_blockers": [],
            "catalog_errors": catalog_errors,
            "coverage": {},
            "evidence": {"status": "FAIL", "parity_status": "BLOCKED"},
        }

    missing_provenance_inputs = [
        label
        for label, value in (
            ("--jar", jar_path),
            ("--research-root", research_root),
            ("--decompiled-root", decompiled_root),
        )
        if value is None
    ]
    source_provenance_status = "UNVERIFIED"
    provenance_blockers = [
        f"{label} was not supplied; target source provenance was not checked"
        for label in missing_provenance_inputs
    ]
    if not missing_provenance_inputs:
        try:
            assert jar_path is not None and research_root is not None and decompiled_root is not None
            entries, sha256, sha1 = jar_inventory(jar_path)
            if jar_path.name != TARGET_NAME or sha256 != TARGET_SHA256 or sha1 != TARGET_SHA1:
                raise ValueError(
                    "target JAR identity mismatch: "
                    f"name={jar_path.name!r}, sha256={sha256}, sha1={sha1}"
                )
            verify_source_provenance(manifest, set(entries), research_root, decompiled_root)
            source_provenance_status = "VERIFIED"
        except (OSError, TypeError, ValueError, KeyError, zipfile.BadZipFile) as error:
            return {
                "status": "FAIL",
                "validation_status": "PASS",
                "source_provenance_status": "FAILED",
                "provenance_errors": [str(error)],
                "provenance_blockers": [],
                "catalog_errors": [],
                "coverage": {},
                "evidence": {"status": "FAIL", "parity_status": "BLOCKED"},
            }
    probe_report = None
    if probe_report_path is not None:
        try:
            probe_report = json.loads(probe_report_path.read_text(encoding="utf-8"))
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
            return {
                "status": "FAIL",
                "validation_status": "PASS",
                "source_provenance_status": source_provenance_status,
                "provenance_blockers": provenance_blockers,
                "catalog_errors": [f"could not load probe report: {error}"],
                "coverage": {},
                "evidence": {"status": "FAIL", "parity_status": "BLOCKED"},
            }
    try:
        fixtures = expand_fixtures(catalog, probe_report)
    except ValueError as error:
        return {
            "status": "FAIL",
            "validation_status": "PASS",
            "source_provenance_status": source_provenance_status,
            "provenance_blockers": provenance_blockers,
            "catalog_errors": [str(error)],
            "coverage": {},
            "evidence": {"status": "FAIL", "parity_status": "BLOCKED"},
        }
    evidence = evaluate_fixtures(fixtures)
    test_map = catalog["storynpcs_test_map"]
    unmapped_junit_fixtures = sorted(
        fixture["fixture_id"] for fixture in catalog["fixtures"]
        if not test_map.get(fixture["fixture_id"])
    )
    return {
        "status": (
            "FAIL" if evidence["status"] != "PASS"
            else "BLOCKED" if source_provenance_status != "VERIFIED"
            else "PASS"
        ),
        "validation_status": "PASS",
        "source_provenance_status": source_provenance_status,
        "provenance_blockers": provenance_blockers,
        "storynpcs_execution_coverage": "INCOMPLETE" if unmapped_junit_fixtures else "MAPPED",
        "catalog_errors": [],
        "coverage": {
            "operation_families": sorted({fixture["operation_family"] for fixture in fixtures}),
            "domains": sorted({fixture["setup"]["domain"] for fixture in fixtures}),
            "fixture_count": len(fixtures),
            "mapped_junit_fixture_count": len(fixtures) - len(unmapped_junit_fixtures),
            "unmapped_junit_fixture_ids": unmapped_junit_fixtures,
            "unmapped_junit_fixture_blockers": [
                {
                    "fixture_id": fixture_id,
                    "owner_issue_ids": catalog["storynpcs_test_blockers"][fixture_id]["owner_issue_ids"],
                    "reason": catalog["storynpcs_test_blockers"][fixture_id]["reason"],
                }
                for fixture_id in unmapped_junit_fixtures
            ],
        },
        "evidence": evidence,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "catalog",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "docs" / "parity" / "fixture-catalog.json",
    )
    parser.add_argument(
        "--probe-report",
        type=Path,
        help="fixture-ID-keyed StoryNPCs observations; untrusted target/comparison fields are ignored",
    )
    parser.add_argument("--jar", type=Path, help="exact target CustomNPCs JAR used for source verification")
    parser.add_argument("--research-root", type=Path, help="root containing the hashed research inventories")
    parser.add_argument("--decompiled-root", type=Path, help="root containing source decompiled from the target JAR")
    return parser.parse_args()


def main() -> int:
    try:
        args = parse_args()
        report = run_catalog(
            args.catalog,
            args.probe_report,
            jar_path=args.jar,
            research_root=args.research_root,
            decompiled_root=args.decompiled_root,
        )
    except (OSError, TypeError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"fixture harness failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
