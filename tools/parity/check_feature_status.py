#!/usr/bin/env python3
"""Derive StoryNPCs feature statuses from code evidence and keep the
feature-comparison doc honest.

Reads ``tools/parity/feature_status_map.json`` — one curated row per
CustomNPCs target feature with observable evidence signals:

- ``files``:      exact repo-relative paths that must exist (domain/schema
                  evidence).
- ``wired``:      ``{"file", "pattern"}`` — regex must match inside that file
                  (runtime consumption: entity tick, payload, command, service).
                  A wired signal pointing at one of the row's own ``files`` is
                  rejected — wiring must come from a consumer.
- ``registered``: ``{"file", "pattern"}`` — registry entry must match
                  (DeferredRegister etc.). Registration alone can never reach
                  the ``tested`` floor — registration is not behavior.
- ``tests``:      glob patterns (repo-relative) that must match ≥1 file.
- ``absent``:     regexes that must have ZERO hits anywhere under
                  ``src/main/java`` — trips when someone lands the feature
                  without updating the map. Comment text is stripped before
                  matching.
- ``absent_exempt``: repo-relative paths skipped by ``absent`` scans — for
                  parity catalogs that legitimately name target classes as
                  mapping data (a name in a catalog row is not a shipped
                  feature). Other files still trip the pattern.

Rows claiming ``declared`` additionally get a 2-hop isolation check: the
declared classes plus any same-package files that reference them must not be
referenced from *other* packages under ``src/main/java``. Known limitation:
this is lexical name matching — fluent consumption via ``var``/getters that
never names the type still slips through, so ``declared`` claims also rely on
honest curation of the map (``resistances-immunities`` was promoted to
``partial`` for exactly this reason).

Observed floor per row:
  missing  — no file/wired/registered hits
  declared — ≥1 file hit, no wired/registered hits
  wired    — ≥1 wired or registered hit
  tested   — ≥1 wired hit AND every declared test glob matched (≥1 listed)

Consistency rules (claimed → allowed floors):
  implemented / implemented-superset → tested
  partial                            → wired, tested
  declared                           → declared
  planned / missing / deviation      → missing
Absent-pattern violations are always findings regardless of floor.

Modes:
  (default)  evaluate signals, print findings; exit 1 on any violation.
  --check    additionally compare the doc's generated marker section
             (``<!-- feature-status:begin -->`` … ``<!-- feature-status:end -->``)
             against freshly generated markdown; fail on drift.
  --write    regenerate the marker section inside the doc in place,
             preserving the file's EOL convention.
"""
from __future__ import annotations

import argparse
import glob as globmod
import json
import re
import sys
from pathlib import Path

STATUS_EMOJI = {
    "implemented": "✅",
    "implemented-superset": "✅ superset",
    "partial": "🟡",
    "declared": "🧩",
    "planned": "📋",
    "missing": "❌",
    "deviation": "🚫 deviation",
}

ALLOWED_FLOORS = {
    "implemented": {"tested"},
    "implemented-superset": {"tested"},
    "partial": {"wired", "tested"},
    "declared": {"declared"},
    "planned": {"missing"},
    "missing": {"missing"},
    "deviation": {"missing"},
}

FLOOR_ORDER = ["missing", "declared", "wired", "tested"]

BEGIN_MARKER = "<!-- feature-status:begin -->"
END_MARKER = "<!-- feature-status:end -->"

MAP_PATH = Path("tools/parity/feature_status_map.json")
DOC_DEFAULT = Path("docs/CUSTOMNPCS_FEATURE_COMPARISON.md")
SRC_GLOB_ROOT = "src/main/java"

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")


def load_map(root: Path) -> tuple[dict, list[str]]:
    errors: list[str] = []
    path = root / MAP_PATH
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        return {}, [f"{MAP_PATH}: cannot load map: {exc}"]
    seen: set[str] = set()
    for i, row in enumerate(data.get("features", [])):
        fid = row.get("id", f"<row {i}>")
        if fid in seen:
            errors.append(f"{fid}: duplicate id")
        seen.add(fid)
        if row.get("status") not in STATUS_EMOJI:
            errors.append(f"{fid}: unknown status {row.get('status')!r}")
        for field in ("feature", "summary", "issue"):
            value = row.get(field, "")
            if not isinstance(value, str):
                errors.append(f"{fid}: {field} must be a string")
            elif BEGIN_MARKER in value or END_MARKER in value:
                errors.append(f"{fid}: {field} contains a marker string")
        evidence = row.get("evidence", {})
        for sig in evidence.get("wired", []) + evidence.get("registered", []):
            if not isinstance(sig.get("file"), str) or not isinstance(sig.get("pattern"), str):
                errors.append(f"{fid}: wired/registered signal needs string file+pattern")
                continue
            try:
                re.compile(sig["pattern"])
            except re.error as exc:
                errors.append(f"{fid}: bad regex {sig['pattern']!r}: {exc}")
        for pattern in evidence.get("absent", []):
            try:
                re.compile(pattern)
            except re.error as exc:
                errors.append(f"{fid}: bad absent regex {pattern!r}: {exc}")
        own = set(evidence.get("files", []))
        for sig in evidence.get("wired", []):
            if sig.get("file") in own:
                errors.append(f"{fid}: wired signal {sig['file']} is its own evidence file")
    return data, errors


def _read_normalized(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def _read_code(path: Path) -> str:
    """Source text with comments stripped — evidence patterns should not match prose."""
    text = _read_normalized(path)
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def _java_files(root: Path):
    src = root / SRC_GLOB_ROOT
    if not src.is_dir():
        return []
    return sorted(p for p in src.rglob("*.java") if p.is_file())


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def evaluate_row(root: Path, row: dict, java_files, code_cache: dict) -> dict:
    feature_id = row["id"]
    evidence = row.get("evidence", {})
    hits = {"files": [], "wired": [], "registered": [], "tests": []}
    misses = {"files": [], "wired": [], "registered": [], "tests": []}
    violations = []

    for rel in evidence.get("files", []):
        (hits if (root / rel).is_file() else misses)["files"].append(rel)

    for key in ("wired", "registered"):
        for sig in evidence.get(key, []):
            path = root / sig["file"]
            if path not in code_cache:
                code_cache[path] = _read_code(path) if path.is_file() else None
            matched = code_cache[path] is not None and re.search(sig["pattern"], code_cache[path])
            (hits if matched else misses)[key].append(sig)

    for pattern in evidence.get("tests", []):
        matched = globmod.glob(str(root / pattern), recursive=True)
        (hits if matched else misses)["tests"].append(pattern)

    exempt = {(root / rel).resolve() for rel in evidence.get("absent_exempt", [])}
    for pattern in evidence.get("absent", []):
        compiled = re.compile(pattern)
        for jf in java_files:
            if jf.resolve() in exempt:
                continue
            if jf not in code_cache:
                code_cache[jf] = _read_code(jf)
            if compiled.search(code_cache[jf]):
                violations.append(
                    f"{feature_id}: absent pattern {pattern!r} matched {jf.relative_to(root).as_posix()}"
                )
                break

    if row["status"] == "declared":
        # 2-hop isolation: the declared classes AND any same-package files that
        # reference them must not be referenced from other packages. A consumer
        # outside the package means the type is wired, not declared.
        stems: dict[str, Path] = {}
        for rel in evidence.get("files", []):
            declared_file = (root / rel).resolve()
            stem = Path(rel).stem
            if not stem or not declared_file.is_file():
                continue
            stems[stem] = declared_file
            own_pkg = declared_file.parent
            stem_ref = re.compile(rf"\b{re.escape(stem)}\b")
            for jf in own_pkg.glob("*.java"):
                resolved = jf.resolve()
                if resolved == declared_file:
                    continue
                if jf not in code_cache:
                    code_cache[jf] = _read_code(jf)
                if stem_ref.search(code_cache[jf]):
                    stems.setdefault(jf.stem, resolved)
        for stem, declared_file in stems.items():
            own_pkg = declared_file.parent
            ref = re.compile(rf"\b{re.escape(stem)}\b")
            for jf in java_files:
                resolved = jf.resolve()
                if resolved in stems.values() or resolved.parent == own_pkg:
                    continue
                if jf not in code_cache:
                    code_cache[jf] = _read_code(jf)
                if ref.search(code_cache[jf]):
                    violations.append(
                        f"{feature_id}: claimed 'declared' but {stem} is referenced by "
                        f"{jf.relative_to(root).as_posix()} — add a wired signal and update the status"
                    )
                    break

    if hits["wired"]:
        floor = "tested" if evidence.get("tests") and hits["tests"] and not misses["tests"] else "wired"
    elif hits["registered"]:
        floor = "wired"
    elif hits["files"]:
        floor = "declared"
    else:
        floor = "missing"

    return {
        "id": feature_id,
        "feature": row["feature"],
        "claimed": row["status"],
        "floor": floor,
        "violations": violations,
        "misses": {k: v for k, v in misses.items() if v},
        "issue": row.get("issue", ""),
        "summary": row.get("summary", ""),
    }


def render_table(results: list[dict]) -> str:
    lines = [
        "| CustomNPCs feature | StoryNPCs today | Status | Owning issue(s) |",
        "|---|---|---|---|",
    ]
    for r in results:
        lines.append(
            f"| {_cell(r['feature'])} | {_cell(r['summary'])} | {STATUS_EMOJI[r['claimed']]} | {_cell(r['issue'])} |"
        )
    counts = {}
    for r in results:
        counts[r["claimed"]] = counts.get(r["claimed"], 0) + 1
    order = list(STATUS_EMOJI)
    lines.append("")
    lines.append(
        "**Status counts:** " + " · ".join(
            f"{STATUS_EMOJI[s]} {counts[s]}" for s in order if counts.get(s)
        ) + f" — {len(results)} rows"
    )
    return "\n".join(lines) + "\n"


def split_marked_section(doc_text: str):
    begin = doc_text.find(BEGIN_MARKER)
    end = doc_text.find(END_MARKER)
    if begin == -1 or end == -1:
        return None
    if end < begin:
        return "reversed"
    head = doc_text[: begin + len(BEGIN_MARKER)]
    tail = doc_text[end:]
    return head, doc_text[begin + len(BEGIN_MARKER) : end], tail


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--check", action="store_true", help="fail if the doc's marked section drifts")
    parser.add_argument("--write", action="store_true", help="regenerate the doc's marked section")
    args = parser.parse_args()

    root = args.root.resolve()
    data, map_errors = load_map(root)
    if map_errors:
        for e in map_errors:
            print(e, file=sys.stderr)
        return 1
    java_files = _java_files(root)
    code_cache: dict = {}
    results = [evaluate_row(root, row, java_files, code_cache) for row in data["features"]]

    failures: list[str] = []
    for r in results:
        failures.extend(r["violations"])
        if r["floor"] not in ALLOWED_FLOORS[r["claimed"]]:
            misses = json.dumps(r["misses"]) if r["misses"] else "none"
            failures.append(
                f"{r['id']}: claimed {r['claimed']} but observed floor is {r['floor']} (misses: {misses})"
            )
        for kind, items in r["misses"].items():
            for item in items:
                ref = item if isinstance(item, str) else f"{item['file']}:{item['pattern']}"
                failures.append(f"{r['id']}: unresolved {kind} evidence: {ref}")

    generated = render_table(results)
    doc_rel = data.get("doc", str(DOC_DEFAULT))
    doc_path = root / doc_rel

    if args.write or args.check:
        try:
            raw_doc = doc_path.read_text(encoding="utf-8")
        except OSError as exc:
            failures.append(f"{doc_rel}: cannot read doc: {exc}")
            raw_doc = ""
        doc = raw_doc.replace("\r\n", "\n")
        parts = split_marked_section(doc) if doc else None

        if args.write and isinstance(parts, tuple):
            head, _old, tail = parts
            eol = "\r\n" if "\r\n" in raw_doc else "\n"
            out = head + "\n" + generated + "\n" + tail
            doc_path.write_text(out.replace("\n", eol), encoding="utf-8", newline="")
            print(f"{doc_rel}: regenerated feature-status section ({len(results)} rows)")

        if args.check:
            if parts is None:
                failures.append(f"{doc_rel}: missing {BEGIN_MARKER}/{END_MARKER} markers")
            elif parts == "reversed":
                failures.append(f"{doc_rel}: end marker appears before begin marker")
            elif parts[1] != "\n" + generated + "\n":
                failures.append(
                    f"{doc_rel}: feature-status section is stale — run `python tools/parity/check_feature_status.py --write`"
                )

    if failures:
        for f in failures:
            print(f, file=sys.stderr)
        return 1
    print(f"feature status check passed ({len(results)} rows, floor distribution: "
          + ", ".join(f"{fl}={sum(1 for r in results if r['floor']==fl)}" for fl in FLOOR_ORDER)
          + ")")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
