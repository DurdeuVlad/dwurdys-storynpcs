#!/usr/bin/env python3
"""Reject unsupported parity and performance completion claims in project docs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


RULES = {
    "mutation_parity": re.compile(
        r"\b100%\s+(?:functional\s+)?(?:mutation\s+)?parity\b|"
        r"\b100\s+percent\s+(?:functional\s+)?(?:mutation\s+)?parity\b",
        re.IGNORECASE,
    ),
    "scripting_api_parity": re.compile(r"(?:public\s+)?scripting\s*/\s*api\s+parity", re.IGNORECASE),
    "crash_safe_progression": re.compile(r"\bcrash[- ]safe\s+progression\b", re.IGNORECASE),
    "large_scale_claim": re.compile(
        r"\b500[- ]NPCs?\b|\b25v25\b|\bLOD/async(?:-path)?(?:/squad)?\b", re.IGNORECASE
    ),
    "complete_ui_parity": re.compile(r"\bcomplete\s+UI\s+parity\b", re.IGNORECASE),
    "complete_feature_parity": re.compile(
        r"\bcomplete\s+(?:CustomNPCs\s+)?(?:feature\s+)?parity\b|"
        r"\bfull\s+(?:CustomNPCs\s+)?parity\b",
        re.IGNORECASE,
    ),
    "all_operations_completion": re.compile(
        r"\ball\s+15\s+operations?(?:\s+have|\s+has|\s+are)\b.*"
        r"\b(?:green|passing|implemented|complete|parity)\b",
        re.IGNORECASE,
    ),
}


def load_exceptions(path: Path) -> set[tuple[str, str, str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    entries = document.get("exceptions", [])
    result: set[tuple[str, str, str]] = set()
    for entry in entries:
        relative_path = str(entry["path"]).replace("\\", "/")
        rule = str(entry["rule"])
        if rule not in RULES:
            raise ValueError(f"unknown truth-gate rule: {rule}")
        line_hash = str(entry["line_sha256"])
        if not re.fullmatch(r"[0-9a-f]{64}", line_hash):
            raise ValueError(f"invalid truth-gate exception line hash: {line_hash}")
        result.add((relative_path, rule, line_hash))
    return result


def check_truth_gate(root: Path, exceptions_path: Path | None = None) -> list[dict[str, Any]]:
    root = root.resolve()
    exceptions_path = exceptions_path or root / "docs" / "parity" / "truth-gate-exceptions.json"
    allowed = load_exceptions(exceptions_path)
    findings: list[dict[str, Any]] = []
    used: set[tuple[str, str, str]] = set()
    for path in sorted(root.rglob("*.md")):
        if any(part in {".git", "build"} for part in path.parts) or path.name == "AGENTS.md":
            continue
        relative_path = path.relative_to(root).as_posix()
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            for rule, pattern in RULES.items():
                if not pattern.search(line):
                    continue
                line_hash = hashlib.sha256(line.encode("utf-8")).hexdigest()
                key = (relative_path, rule, line_hash)
                if key in allowed:
                    used.add(key)
                    continue
                findings.append({"path": relative_path, "line": line_number, "rule": rule, "text": line.strip()})
    unused = sorted(allowed - used)
    findings.extend(
        {"path": path, "line": 0, "rule": rule, "text": "unused exception"}
        for path, rule, _line_hash in unused
    )
    return findings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--exceptions", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    findings = check_truth_gate(args.root, args.exceptions)
    if findings:
        for finding in findings:
            print(
                f"{finding['path']}:{finding['line']}: {finding['rule']}: {finding['text']}",
                file=sys.stderr,
            )
        return 1
    print("truth gate passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
