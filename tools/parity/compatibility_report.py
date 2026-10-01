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


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


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
                         surface_map: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
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
    report = expand_compatibility(manifest, surface_map)
    out = root / "docs" / "parity" / "reports" / "compatibility-report.json"
    write_report(report, out)
    print(f"validation={report['validation_status']} rows={report['summary']['mapped_rows']}/"
          f"{report['summary']['total_rows']} by_state={report['summary']['by_state']} -> {out}")
    return 0 if report["validation_status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
