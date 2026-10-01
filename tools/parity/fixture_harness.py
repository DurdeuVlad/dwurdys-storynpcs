#!/usr/bin/env python3
"""Validate and report the executable CustomNPCs parity fixture catalog."""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
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


FINGERPRINT_DIRS = ("src/main", "src/test", "tools/parity", "docs/creator")
FINGERPRINT_FILES = (
    "build.gradle",
    "gradle.properties",
    "settings.gradle",
    "gradle/wrapper/gradle-wrapper.properties",
    "docs/parity/evidence-schema.json",
    "docs/parity/fixture-catalog.json",
    "docs/parity/target-runtime-import.json",
    "docs/parity/target-surface-manifest.json",
    "docs/parity/truth-gate-exceptions.json",
)


def _fingerprint_content(path: Path) -> bytes:
    """Canonical content bytes for fingerprinting.

    Text payloads are normalized to LF so the fingerprint is identical on
    CRLF (``core.autocrlf`` Windows) and LF checkouts; payloads that do not
    decode as UTF-8 are hashed verbatim.
    """
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw
    return text.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")


def source_fingerprint(root: Path) -> str:
    paths = {root / name for name in FINGERPRINT_FILES if (root / name).is_file()}
    for directory in FINGERPRINT_DIRS:
        base = root / directory
        if base.is_dir():
            paths.update(
                path for path in base.rglob("*")
                if path.is_file()
                and "__pycache__" not in path.parts
                and (directory != "tools/parity" or path.suffix == ".py")
            )
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content = _fingerprint_content(path)
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


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


TARGET_IMPORT_SCHEMA = "storynpcs.target-runtime-import/v1"
IMPORT_PROVENANCE_FIELDS = {"evidence_label", "source_document", "source_section", "recorded_on"}


def load_target_import(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("target-runtime import must be an object")
    return value


def validate_target_import(
    document: dict[str, Any],
    catalog_fixture_ids: set[str],
) -> tuple[dict[str, dict[str, Any]], list[str]]:
    """Validate the versioned target-runtime import artifact.

    Returns a fixture_id -> observations map plus errors. Only observations
    carrying full provenance and an exact VERIFIED_TARGET_RUNTIME label are
    importable; anything else fails validation so a provenance-less OBSERVED
    claim can never reach the emitted fixtures.
    """
    indexed: dict[str, dict[str, Any]] = {}
    errors: list[str] = []
    if document.get("schema") != TARGET_IMPORT_SCHEMA:
        errors.append(f"target-runtime import schema must be {TARGET_IMPORT_SCHEMA}")
    if not isinstance(document.get("import_version"), str) or not document["import_version"].strip():
        errors.append("target-runtime import requires a non-empty import_version")
    source = document.get("source")
    if not isinstance(source, dict):
        errors.append("target-runtime import requires a source object")
    else:
        if source.get("target_jar_sha256") != TARGET_SHA256:
            errors.append("target-runtime import target_jar_sha256 does not match the pinned target JAR")
        if not isinstance(source.get("target_jar"), str) or source.get("target_jar") != TARGET_NAME:
            errors.append("target-runtime import target_jar must be the pinned target JAR name")
        for field in ("repository", "documents", "runtime"):
            if field not in source:
                errors.append(f"target-runtime import source is missing {field}")
    fixtures = document.get("fixtures")
    if not isinstance(fixtures, list):
        return indexed, errors + ["target-runtime import fixtures must be a list"]
    for index, entry in enumerate(fixtures):
        prefix = f"target_import.fixtures[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{prefix} must be an object")
            continue
        fixture_id = entry.get("fixture_id")
        if not isinstance(fixture_id, str) or not fixture_id.strip():
            errors.append(f"{prefix}.fixture_id must be a non-empty string")
            continue
        if fixture_id not in catalog_fixture_ids:
            errors.append(f"{prefix} references unknown fixture: {fixture_id}")
            continue
        if fixture_id in indexed:
            errors.append(f"target-runtime import contains duplicate fixture_id: {fixture_id}")
            continue
        observations = entry.get("observations")
        if not isinstance(observations, list) or not observations:
            errors.append(f"{prefix} must list at least one observation")
            continue
        entry_errors = False
        for obs_index, observation in enumerate(observations):
            obs_prefix = f"{prefix}.observations[{obs_index}]"
            if not isinstance(observation, dict):
                errors.append(f"{obs_prefix} must be an object")
                entry_errors = True
                continue
            if not isinstance(observation.get("probe"), str) or not observation["probe"].strip():
                errors.append(f"{obs_prefix} requires a non-empty probe label")
                entry_errors = True
            result = observation.get("observed_result")
            if (
                result is None
                or (isinstance(result, str) and not result.strip())
                or (isinstance(result, (dict, list)) and not result)
            ):
                errors.append(f"{obs_prefix} requires a non-empty observed_result")
                entry_errors = True
            provenance = observation.get("provenance")
            if not isinstance(provenance, dict):
                errors.append(f"{obs_prefix} requires a provenance object")
                entry_errors = True
            else:
                missing = sorted(IMPORT_PROVENANCE_FIELDS - set(provenance))
                if missing:
                    errors.append(f"{obs_prefix}.provenance missing fields: {missing}")
                    entry_errors = True
                if provenance.get("evidence_label") != "VERIFIED_TARGET_RUNTIME":
                    errors.append(
                        f"{obs_prefix}.provenance.evidence_label must be VERIFIED_TARGET_RUNTIME"
                    )
                    entry_errors = True
                for field in ("source_document", "source_section", "recorded_on"):
                    value = provenance.get(field)
                    if not isinstance(value, str) or not value.strip():
                        errors.append(f"{obs_prefix}.provenance.{field} must be a non-empty string")
                        entry_errors = True
        if not entry_errors:
            indexed[fixture_id] = entry
    return indexed, errors


def _imported_target_probe(entry: dict[str, Any], import_doc: dict[str, Any]) -> dict[str, Any]:
    observations = [
        {
            "probe": observation["probe"],
            "command": observation.get("command"),
            "observed_result": observation["observed_result"],
            "provenance": observation["provenance"],
        }
        for observation in entry["observations"]
    ]
    source = import_doc.get("source", {})
    return {
        "status": "OBSERVED",
        "result": {
            "observations": observations,
            "execution_scope": (
                "Recorded dedicated-server console probes on the exact target JAR; "
                "no live probe was re-executed by StoryNPCs tooling"
            ),
        },
        "provenance": {
            "evidence_label": "VERIFIED_TARGET_RUNTIME",
            "imported_from": "docs/parity/target-runtime-import.json",
            "import_version": import_doc.get("import_version"),
            "source_repository": source.get("repository"),
            "source_documents": source.get("documents"),
            "target_jar_sha256": source.get("target_jar_sha256"),
            "runtime": source.get("runtime"),
            "observations": [o["provenance"] for o in entry["observations"]],
        },
    }


def expand_fixtures(
    catalog: dict[str, Any],
    probe_report: dict[str, Any] | None = None,
    target_import: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    template = deepcopy(BASELINE_EVIDENCE_TEMPLATE)
    probe_entries, probe_errors = _probe_report_entries(probe_report)
    if probe_errors:
        raise ValueError("; ".join(probe_errors))
    catalog_ids = {entry["fixture_id"] for entry in catalog["fixtures"]}
    unknown_probe_ids = sorted(set(probe_entries) - catalog_ids)
    if unknown_probe_ids:
        raise ValueError("probe report references unknown fixtures: " + ", ".join(unknown_probe_ids))
def _resolve_target_probe(
    fixture_id: str,
    import_entries: dict[str, dict[str, Any]],
    target_import: dict[str, Any] | None,
    template: dict[str, Any],
) -> dict[str, Any]:
    import_entry = import_entries.get(fixture_id)
    if import_entry is not None:
        return _imported_target_probe(import_entry, target_import)
    if target_import is not None:
        return {
            "status": "UNAVAILABLE",
            "reason": (
                "no VERIFIED_TARGET_RUNTIME observation in the committed "
                "import artifact covers this fixture"
            ),
            "next_evidence": (
                "run a target runtime probe and record it in "
                "docs/parity/target-runtime-import.json with provenance"
            ),
        }
    return dict(template.get("target_probe", {}))


def target_probe_map(
    catalog: dict[str, Any],
    target_import: dict[str, Any] | None,
) -> dict[str, dict[str, Any]]:
    """fixture_id -> populated target_probe for every catalog fixture.

    Used by the probe-report generator so emitted rows show the imported
    target observation (or an explicit not-imported reason) per fixture.
    """
    catalog_ids = {entry["fixture_id"] for entry in catalog["fixtures"]}
    import_entries: dict[str, dict[str, Any]] = {}
    if target_import is not None:
        import_entries, import_errors = validate_target_import(target_import, catalog_ids)
        if import_errors:
            raise ValueError("; ".join(import_errors))
    return {
        fixture_id: _resolve_target_probe(
            fixture_id, import_entries, target_import, BASELINE_EVIDENCE_TEMPLATE)
        for fixture_id in catalog_ids
    }


def expand_fixtures(
    catalog: dict[str, Any],
    probe_report: dict[str, Any] | None = None,
    target_import: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    template = deepcopy(BASELINE_EVIDENCE_TEMPLATE)
    probe_entries, probe_errors = _probe_report_entries(probe_report)
    if probe_errors:
        raise ValueError("; ".join(probe_errors))
    catalog_ids = {entry["fixture_id"] for entry in catalog["fixtures"]}
    unknown_probe_ids = sorted(set(probe_entries) - catalog_ids)
    if unknown_probe_ids:
        raise ValueError("probe report references unknown fixtures: " + ", ".join(unknown_probe_ids))
    import_entries: dict[str, dict[str, Any]] = {}
    if target_import is not None:
        import_entries, import_errors = validate_target_import(target_import, catalog_ids)
        if import_errors:
            raise ValueError("; ".join(import_errors))
    fixtures: list[dict[str, Any]] = []
    for entry in catalog["fixtures"]:
        probe = probe_entries.get(entry["fixture_id"], {})
        storynpcs_probe = dict(template.get("storynpcs_probe", {}))
        # Probe JSON is caller-controlled input; it may carry StoryNPCs
        # observations only. Target observations arrive exclusively through the
        # committed, schema-validated import artifact — never through
        # caller-supplied probe JSON.
        if isinstance(probe.get("storynpcs_probe"), dict):
            storynpcs_probe.update(deepcopy(probe["storynpcs_probe"]))
        target_probe = _resolve_target_probe(
            entry["fixture_id"], import_entries, target_import, template)
        if target_probe.get("status") == "OBSERVED":
            comparison = {
                "rule": "imported-target-observation",
                "outcome": "NOT_COMPARABLE",
                "reason": (
                    "imported observations record target behavior verbatim; JSON "
                    "equality with JUnit outcomes is not a valid parity comparison"
                ),
            }
            evidence_state = "VERIFIED_TARGET_RUNTIME"
        else:
            comparison = dict(template.get("comparison", {}))
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
    target_import_path: Path | None = None,
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
    if target_import_path is None:
        target_import_path = (
            repository_root / "docs" / "parity" / "target-runtime-import.json"
        )
    target_import = None
    if target_import_path.is_file():
        try:
            target_import = load_target_import(target_import_path)
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
            return {
                "status": "FAIL",
                "validation_status": "PASS",
                "source_provenance_status": source_provenance_status,
                "provenance_blockers": provenance_blockers,
                "catalog_errors": [f"could not load target-runtime import: {error}"],
                "coverage": {},
                "evidence": {"status": "FAIL", "parity_status": "BLOCKED"},
            }
    try:
        fixtures = expand_fixtures(catalog, probe_report, target_import)
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
    imported_fixture_ids = sorted(
        fixture["fixture_id"] for fixture in fixtures
        if fixture["target_probe"].get("status") == "OBSERVED"
    )
    return {
        "status": (
            "FAIL" if evidence["status"] != "PASS"
            else "BLOCKED" if source_provenance_status != "VERIFIED"
            else "PASS"
        ),
        "target_import": {
            "path": (
                target_import_path.relative_to(repository_root).as_posix()
                if target_import is not None else None
            ),
            "import_version": (
                target_import.get("import_version") if target_import is not None else None
            ),
            "fixtures_imported": imported_fixture_ids,
            "fixtures_without_import": sorted(
                fixture["fixture_id"] for fixture in fixtures
                if fixture["fixture_id"] not in set(imported_fixture_ids)
            ),
        },
        "validation_status": "PASS",
        "source_provenance_status": source_provenance_status,
        "provenance_blockers": provenance_blockers,
        "storynpcs_execution_coverage": "INCOMPLETE" if unmapped_junit_fixtures else "MAPPED",
        "catalog_errors": [],
        "fixtures": fixtures,
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
