#!/usr/bin/env python3
"""Validate parity fixture evidence and prevent unsupported green claims."""

from __future__ import annotations

from copy import deepcopy
import json
import re
from typing import Any


EVIDENCE_STATES = {
    "VERIFIED_TARGET_SOURCE",
    "VERIFIED_TARGET_RUNTIME",
    "VERIFIED_STORYNPCS_RUNTIME",
    "VERIFIED_PARITY",
    "UNVERIFIED_TARGET_RUNTIME",
    "INTENTIONAL_DEVIATION",
    "UNKNOWN",
}
TARGET_PROBE_STATUSES = {"OBSERVED", "UNAVAILABLE", "BLOCKED", "NOT_RUN"}
STORYNPCS_PROBE_STATUSES = {"OBSERVED", "BLOCKED", "NOT_RUN"}
REQUIRED_FIXTURE_FIELDS = {
    "fixture_id",
    "issue_ids",
    "target_symbol",
    "operation_family",
    "setup",
    "required_layers",
    "target_probe",
    "storynpcs_probe",
    "comparison",
    "evidence_state",
    "failure_context",
}
FIXTURE_ID_PATTERN = re.compile(r"^P\d+-\d+\.[A-Za-z0-9][A-Za-z0-9._-]*$")
ISSUE_ID_PATTERN = re.compile(r"^P\d+-\d+$")
ALLOWED_LAYERS = {"server", "client", "target-runtime", "storynpcs-runtime", "persistence"}


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _meaningful_result(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, dict):
        return bool(value) and any(_meaningful_result(item) for item in value.values())
    if isinstance(value, list):
        return bool(value) and any(_meaningful_result(item) for item in value)
    return True


def _same_json_result(left: Any, right: Any) -> bool:
    try:
        left_encoded = json.dumps(
            left, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
        )
        right_encoded = json.dumps(
            right, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
        )
    except (TypeError, ValueError):
        return False
    return left_encoded == right_encoded


def _probe_errors(name: str, probe: Any, statuses: set[str]) -> list[str]:
    if not isinstance(probe, dict):
        return [f"{name} probe must be an object"]
    errors: list[str] = []
    status = probe.get("status")
    if not isinstance(status, str) or status not in statuses:
        errors.append(f"{name} probe status must be one of {sorted(statuses)}")
    if isinstance(status, str) and status in {"UNAVAILABLE", "BLOCKED"}:
        if not _nonempty_string(probe.get("reason")):
            errors.append(f"{name} {status.lower()} probe requires reason")
        if not _nonempty_string(probe.get("next_evidence")):
            errors.append(f"{name} {status.lower()} probe requires next_evidence")
    if status == "OBSERVED" and ("result" not in probe or not _meaningful_result(probe.get("result"))):
        errors.append(f"{name} observed probe requires a meaningful result")
    return errors


def validate_fixture(fixture: Any) -> list[str]:
    if not isinstance(fixture, dict):
        return ["fixture must be an object"]
    errors = [f"missing required field: {field}" for field in sorted(REQUIRED_FIXTURE_FIELDS - fixture.keys())]
    if errors:
        return errors

    if not _nonempty_string(fixture["fixture_id"]):
        errors.append("fixture_id must be a non-empty string")
    elif not FIXTURE_ID_PATTERN.fullmatch(fixture["fixture_id"]):
        errors.append("fixture_id must match P<priority>-<number>.<name>")
    if (
        not isinstance(fixture["issue_ids"], list)
        or not fixture["issue_ids"]
        or not all(isinstance(issue_id, str) and ISSUE_ID_PATTERN.fullmatch(issue_id) for issue_id in fixture["issue_ids"])
    ):
        errors.append("issue_ids must be a non-empty list")
    if not _nonempty_string(fixture["target_symbol"]):
        errors.append("target_symbol must be a non-empty string")
    if not _nonempty_string(fixture["operation_family"]):
        errors.append("operation_family must be a non-empty string")
    if not isinstance(fixture["setup"], dict):
        errors.append("setup must be an object")
    if (
        not isinstance(fixture["required_layers"], list)
        or not fixture["required_layers"]
        or not all(isinstance(layer, str) and layer in ALLOWED_LAYERS for layer in fixture["required_layers"])
    ):
        errors.append("required_layers must be a non-empty list")

    failure_context = fixture["failure_context"]
    if not isinstance(failure_context, dict):
        errors.append("failure_context must be an object")
    else:
        for field in {"issue_ids", "target_symbol", "input", "expected_result", "actual_result", "evidence_state"}:
            if field not in failure_context:
                errors.append(f"failure_context missing {field}")
        if (
            not isinstance(failure_context.get("issue_ids"), list)
            or failure_context.get("issue_ids") != fixture["issue_ids"]
        ):
            errors.append("failure_context issue_ids must match fixture issue_ids")
        if failure_context.get("target_symbol") != fixture["target_symbol"]:
            errors.append("failure_context target_symbol must match fixture target_symbol")
        if failure_context.get("evidence_state") != fixture["evidence_state"]:
            errors.append("failure_context evidence_state must match fixture evidence_state")

    errors.extend(_probe_errors("target", fixture["target_probe"], TARGET_PROBE_STATUSES))
    errors.extend(_probe_errors("StoryNPCs", fixture["storynpcs_probe"], STORYNPCS_PROBE_STATUSES))

    comparison = fixture["comparison"]
    if not isinstance(comparison, dict) or not _nonempty_string(comparison.get("rule")):
        errors.append("comparison requires a rule")
    elif comparison.get("outcome") is not None and (
        not isinstance(comparison.get("outcome"), str)
        or comparison.get("outcome") not in {"MATCH", "MISMATCH", "NOT_COMPARABLE"}
    ):
        errors.append("comparison outcome must be MATCH, MISMATCH, or NOT_COMPARABLE")

    state = fixture["evidence_state"]
    if not isinstance(state, str) or state not in EVIDENCE_STATES:
        errors.append(f"unknown evidence state: {state}")

    target_status = fixture["target_probe"].get("status") if isinstance(fixture["target_probe"], dict) else None
    story_status = (
        fixture["storynpcs_probe"].get("status") if isinstance(fixture["storynpcs_probe"], dict) else None
    )
    outcome = comparison.get("outcome") if isinstance(comparison, dict) else None

    if state == "VERIFIED_TARGET_RUNTIME" and target_status != "OBSERVED":
        errors.append("VERIFIED_TARGET_RUNTIME requires an observed target probe")
    if state == "VERIFIED_STORYNPCS_RUNTIME" and story_status != "OBSERVED":
        errors.append("VERIFIED_STORYNPCS_RUNTIME requires an observed StoryNPCs probe")
    if state == "VERIFIED_PARITY":
        if target_status != "OBSERVED" or story_status != "OBSERVED":
            errors.append("VERIFIED_PARITY requires observed target and StoryNPCs probes")
        if outcome != "MATCH":
            errors.append("VERIFIED_PARITY requires comparison outcome MATCH")
        if (
            target_status == "OBSERVED"
            and story_status == "OBSERVED"
            and not _same_json_result(
                fixture["target_probe"].get("result"), fixture["storynpcs_probe"].get("result")
            )
        ):
            errors.append("VERIFIED_PARITY requires equal observed target and StoryNPCs results")
    if state == "UNVERIFIED_TARGET_RUNTIME" and (
        not isinstance(target_status, str)
        or target_status not in {"UNAVAILABLE", "BLOCKED", "NOT_RUN"}
    ):
        errors.append("UNVERIFIED_TARGET_RUNTIME requires a non-observed target probe")
    if state == "INTENTIONAL_DEVIATION":
        if not _nonempty_string(fixture.get("rationale")):
            errors.append("INTENTIONAL_DEVIATION requires rationale")
        if not _nonempty_string(fixture.get("migration_impact")):
            errors.append("INTENTIONAL_DEVIATION requires migration_impact")
    return errors


def evaluate_fixtures(fixtures: list[dict[str, Any]]) -> dict[str, Any]:
    if not fixtures:
        return {
            "status": "FAIL",
            "parity_status": "BLOCKED",
            "certification_eligible": False,
            "errors": [{"fixture_id": None, "message": "fixtures must not be empty"}],
            "certification_blockers": [],
            "fixtures": [],
        }
    errors: list[dict[str, Any]] = []
    rows: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for index, fixture in enumerate(fixtures):
        row = {"index": index, "fixture_id": fixture.get("fixture_id") if isinstance(fixture, dict) else None}
        fixture_errors = validate_fixture(fixture)
        fixture_id = row["fixture_id"]
        if isinstance(fixture_id, str) and fixture_id in seen_ids:
            fixture_errors.append("duplicate fixture_id")
        if isinstance(fixture_id, str):
            seen_ids.add(fixture_id)
        row["evidence_state"] = fixture.get("evidence_state") if isinstance(fixture, dict) else None
        row["errors"] = fixture_errors
        row["failure_context"] = fixture.get("failure_context") if isinstance(fixture, dict) else None
        rows.append(row)
        errors.extend(
            {
                "fixture_id": fixture_id,
                "message": message,
                "failure_context": row["failure_context"],
            }
            for message in fixture_errors
        )

    certification_blockers = [
        {"fixture_id": row["fixture_id"], "evidence_state": row["evidence_state"], "reason": reason}
        for row in rows
        for reason in (
            ["fixture has validation errors"] if row["errors"] else []
        )
    ]
    for row in rows:
        if not row["errors"] and row["evidence_state"] not in {"VERIFIED_PARITY", "INTENTIONAL_DEVIATION"}:
            certification_blockers.append(
                {
                    "fixture_id": row["fixture_id"],
                    "evidence_state": row["evidence_state"],
                    "reason": "fixture is not verified parity or an approved intentional deviation",
                }
            )
    certification_eligible = not errors and not certification_blockers
    return {
        "status": "PASS" if not errors else "FAIL",
        "parity_status": "VERIFIED" if certification_eligible else "BLOCKED",
        "certification_eligible": certification_eligible,
        "errors": errors,
        "certification_blockers": certification_blockers,
        "fixtures": rows,
    }


def load_and_evaluate(document: dict[str, Any]) -> dict[str, Any]:
    """Evaluate a JSON evidence report without mutating its input."""
    if not isinstance(document, dict):
        return {
            "status": "FAIL",
            "parity_status": "BLOCKED",
            "certification_eligible": False,
            "errors": [{"fixture_id": None, "message": "evidence report must be an object"}],
            "certification_blockers": [],
            "fixtures": [],
        }
    value = deepcopy(document)
    fixtures = value.get("fixtures")
    if not isinstance(fixtures, list):
        return {
            "status": "FAIL",
            "parity_status": "BLOCKED",
            "certification_eligible": False,
            "errors": [{"fixture_id": None, "message": "fixtures must be a list"}],
            "certification_blockers": [],
            "fixtures": [],
        }
    return evaluate_fixtures(fixtures)
