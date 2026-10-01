#!/usr/bin/env python3
"""P11-2: expand the StoryNPCs surface map over the target manifest.

Every manifest inventory row resolves to exactly one terminal mapping state.
The report records that no row is unmapped, that deviations carry rationale
plus migration impact, and that every behavioral row's parity claim stays
UNVERIFIED_TARGET_RUNTIME while target probes are unavailable.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

TERMINAL_STATES = {
    "INVENTORY_ONLY",
    "MAPPED_STORYNPCS_OBSERVED",
    "INTENTIONAL_DEVIATION",
    "UNVERIFIED_STORYNPCS",
    "UNKNOWN",
}
STATES_REQUIRING_EVIDENCE = {"UNVERIFIED_STORYNPCS", "UNKNOWN"}
INVENTORY_ID_PATTERN = re.compile(r"^target\.[a-z_]+\.\d{4}$")

# Issue #124: MAPPED_STORYNPCS_OBSERVED is *feature-mapped* — the row must name
# concrete, verifiable StoryNPCs artifacts that implement the row's function.
# storynpcs_ref is a whitespace-separated token list; every token must carry a
# recognized kind prefix and resolve against the repository:
#   path:<repo-relative file/dir>   — exists on disk
#   class:<fqcn>                    — src/main/java or src/test/java source file
#   test:<fqcn>                     — src/test/java source file
#   op:<canonical-operation>        — registered in CapabilityRegistry
# Milestone labels ("P8-3"), prose, or bare names are unverifiable and fail.
REF_KINDS = ("path", "class", "test", "op")
CAPABILITY_REGISTRY = "src/main/java/com/storynpcs/service/CapabilityRegistry.java"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _canonical_operations(root: Path) -> frozenset[str]:
    """Operation names registered in CapabilityRegistry's Map.entry table."""
    registry = root / CAPABILITY_REGISTRY
    if not registry.is_file():
        return frozenset()
    return frozenset(re.findall(r'Map\.entry\("([^"]+)"', registry.read_text(encoding="utf-8")))


def _class_path_exists(root: Path, fqcn: str, test_only: bool) -> bool:
    if not re.fullmatch(r"[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)+", fqcn):
        return False
    rel = Path(*fqcn.split(".")).with_suffix(".java")
    sources = ("src/test/java",) if test_only else ("src/main/java", "src/test/java")
    return any((root / base / rel).is_file() for base in sources)


def verify_storynpcs_ref(ref: str, root: Path,
                         operations: frozenset[str] | None = None) -> list[str]:
    """Validate one storynpcs_ref token list. Returns a list of problems
    (empty = every token names a verifiable repository artifact)."""
def _verify_ref_token(token: str, root: Path, resolved_root: Path,
                      operations: frozenset[str]) -> str | None:
    """Return a problem string for one artifact token, or None if verified."""
    kind, sep, value = token.partition(":")
    if not sep or kind not in REF_KINDS or not value.strip():
        return (f"{token!r}: unverifiable ref token (expected "
                + "/".join(f"{k}:" for k in REF_KINDS) + ")")
    if kind == "path":
        candidate = Path(value)
        resolved = (resolved_root / candidate).resolve()
        if (candidate.is_absolute() or ".." in candidate.parts
                or resolved == resolved_root
                or resolved_root not in resolved.parents):
            return f"{token!r}: path must be a repo-relative path inside the repo"
        if not resolved.exists():
            return f"{token!r}: path does not exist"
        return None
    if kind == "class" and not _class_path_exists(root, value, test_only=False):
        return f"{token!r}: no java source for class"
    if kind == "test" and not _class_path_exists(root, value, test_only=True):
        return f"{token!r}: no java test source for class"
    if kind == "op" and value not in operations:
        return f"{token!r}: not a registered canonical operation"
    return None


def verify_storynpcs_ref(ref: str, root: Path,
                         operations: frozenset[str] | None = None) -> list[str]:
    """Validate one storynpcs_ref token list. Returns a list of problems
    (empty = every token names a verifiable repository artifact)."""
    tokens = str(ref).split()
    if not tokens:
        return ["empty storynpcs_ref — MAPPED rows must name concrete artifacts"]
    ops = operations if operations is not None else _canonical_operations(root)
    resolved_root = root.resolve()
    return [problem for token in tokens
            if (problem := _verify_ref_token(token, root, resolved_root, ops))]


def _resolve_mapping(row: dict[str, Any], surface_map: dict[str, Any]) -> dict[str, Any] | None:
    """Row override (by inventory_id or symbol glob prefix) wins over surface default."""
    for override in surface_map.get("row_overrides", []):
        if override.get("inventory_id") == row.get("inventory_id"):
            return override
        pattern = override.get("symbol_prefix")
        if pattern and str(row.get("symbol", "")).startswith(pattern):
            return override
    return surface_map.get("surface_defaults", {}).get(row.get("surface"))


def expand_compatibility(manifest: dict[str, Any],
                         surface_map: dict[str, Any],
                         root: Path | None = None) -> dict[str, Any]:
    root = root or Path(__file__).resolve().parents[2]
    resolved_root = root.resolve()
    operations = _canonical_operations(root)
    errors: list[str] = []
    ref_checks = {"rows": 0, "verified_tokens": 0, "failed": []}
    deviations = {d.get("deviation_id"): d for d in surface_map.get("deviations", [])}
    for deviation_id, deviation in deviations.items():
        if not str(deviation.get("rationale", "")).strip():
            errors.append(f"deviation {deviation_id} missing rationale")
        if not str(deviation.get("migration_impact", "")).strip():
            errors.append(f"deviation {deviation_id} missing migration_impact")

    rows_out: list[dict[str, Any]] = []
    by_state: dict[str, int] = {}
    by_surface: dict[str, dict[str, int]] = {}
    unmapped: list[str] = []
    blocked_rows: list[str] = []
    seen_ids: set[str] = set()

    for surface, rows in sorted(manifest.get("surfaces", {}).items()):
        for row in rows:
            inventory_id = row.get("inventory_id")
            if not isinstance(inventory_id, str) or not INVENTORY_ID_PATTERN.fullmatch(inventory_id):
                errors.append(f"{surface}: malformed inventory_id {inventory_id!r}")
                continue
            if inventory_id in seen_ids:
                errors.append(f"duplicate inventory_id {inventory_id}")
                continue
            seen_ids.add(inventory_id)

            mapping = _resolve_mapping(row, surface_map)
            if mapping is None:
                unmapped.append(inventory_id)
                continue
            state = mapping.get("mapping_state")
            if state not in TERMINAL_STATES:
                errors.append(f"{inventory_id}: non-terminal mapping_state {state!r}")
                continue
            if state == "MAPPED_STORYNPCS_OBSERVED":
                ref_checks["rows"] += 1
                ref_tokens = str(mapping.get("storynpcs_ref", "")).split()
                problems = verify_storynpcs_ref(
                    mapping.get("storynpcs_ref", ""), root, operations)
                ref_checks["verified_tokens"] += sum(
                    1 for token in ref_tokens
                    if _verify_ref_token(token, root, resolved_root, operations) is None)
                for problem in problems:
                    errors.append(f"{inventory_id}: MAPPED_STORYNPCS_OBSERVED "
                                  f"storynpcs_ref {problem}")
                    ref_checks["failed"].append(
                        {"inventory_id": inventory_id, "problem": problem})
            if state in STATES_REQUIRING_EVIDENCE and not str(
                    mapping.get("evidence_required", "")).strip():
                errors.append(f"{inventory_id}: {state} row must name evidence_required")
            if state == "INTENTIONAL_DEVIATION":
                deviation_id = mapping.get("deviation_id")
                if deviation_id not in deviations:
                    errors.append(f"{inventory_id}: unknown deviation_id {deviation_id!r}")

            # Every behavioral row's parity claim stays blocked by the missing
            # target probe — never upgraded by implementation-side evidence.
            parity_state = ("NOT_APPLICABLE" if state == "INVENTORY_ONLY"
                            else "UNVERIFIED_TARGET_RUNTIME")
            if parity_state == "UNVERIFIED_TARGET_RUNTIME":
                blocked_rows.append(inventory_id)

            rows_out.append({
                "inventory_id": inventory_id,
                "surface": surface,
                "symbol": row.get("symbol"),
                "mapping_state": state,
                "storynpcs_ref": mapping.get("storynpcs_ref", ""),
                "parity_state": parity_state,
                "deviation_id": mapping.get("deviation_id"),
                "evidence_required": mapping.get("evidence_required", ""),
            })
            by_state[state] = by_state.get(state, 0) + 1
            surface_counts = by_surface.setdefault(surface, {})
            surface_counts[state] = surface_counts.get(state, 0) + 1

    for inventory_id in sorted(unmapped):
        errors.append(f"unmapped target row: {inventory_id}")

    return {
        "validation_status": "FAIL" if errors else "PASS",
        "errors": errors,
        "summary": {
            "total_rows": len(rows_out) + len(unmapped),
            "mapped_rows": len(rows_out),
            "unmapped_rows": len(unmapped),
            "by_state": by_state,
            "by_surface": by_surface,
            "parity_blocked_rows": len(blocked_rows),
        },
        "mapping_integrity": {
            "mapped_rows_checked": ref_checks["rows"],
            "verified_ref_tokens": ref_checks["verified_tokens"],
            "failed_refs": ref_checks["failed"],
        },
        "rows": rows_out,
    }


def write_report(report: dict[str, Any], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # newline="\n": generated report bytes stay LF on every platform (#127).
    out_path.write_text(
        json.dumps(report, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    manifest = load_json(root / "docs" / "parity" / "target-surface-manifest.json")
    surface_map = load_json(root / "docs" / "parity" / "storynpcs-surface-map.json")
    report = expand_compatibility(manifest, surface_map, root)
    out = root / "docs" / "parity" / "reports" / "compatibility-report.json"
    write_report(report, out)
    print(f"validation={report['validation_status']} rows={report['summary']['mapped_rows']}/"
          f"{report['summary']['total_rows']} by_state={report['summary']['by_state']} -> {out}")
    return 0 if report["validation_status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
