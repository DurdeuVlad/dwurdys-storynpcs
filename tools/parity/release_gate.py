#!/usr/bin/env python3
"""P11-3: adversarial release gate.

Checks the whole parity program against the register, catalog, manifest,
compatibility map, benchmark artifacts, and project docs. A finding is a
string; an empty finding list means the checklist item passed. The gate never
upgrades evidence: unavailable target probes keep parity BLOCKED.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

try:
    from tools.parity.fixture_harness import source_fingerprint
except ModuleNotFoundError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.parity.fixture_harness import source_fingerprint

ISSUE_HEADING = re.compile(r"^### (P\d+-\d+) — ")
MILESTONE_HEADING = re.compile(r"^## (M\d+) — ")
ISSUE_TOKEN = re.compile(r"\bP(\d+)-(\d+)\b")
MILESTONE_TOKEN = re.compile(r"\bM(\d+)(?:-(\d+))?\b")
STATUS_LINE = re.compile(r"^Status: `?([A-Z\-]+)`?", re.MULTILINE)
DEPENDENCIES_LINE = re.compile(r"\*\*Dependencies and open decisions:\*\*\s*(.+)")

DONE_STATES = {"DONE", "DONE-LOCAL"}
BLOCKED_STATES = {"BLOCKED-ACCEPTED"}
UNFINISHED_STATES = {"BLOCKED", "IN-PROGRESS", "IN-REVIEW"}

REQUIRED_BENCHMARKS = ("population", "siege", "stress")


def load_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_json(path: Path) -> Any:
    return json.loads(load_text(path))


def parse_register(register_path: Path) -> dict[str, Any]:
    """Split the register into milestone -> ordered issue list and issue -> body."""
    text = load_text(register_path)
    issues: dict[str, str] = {}
    milestones: dict[str, list[str]] = {}
    current_milestone = "UNNUMBERED"
    current_issue: str | None = None
    body: list[str] = []

    def flush():
        nonlocal body
        if current_issue is not None:
            issues[current_issue] = "\n".join(body)
        body = []

    for line in text.splitlines():
        m = MILESTONE_HEADING.match(line)
        if m:
            flush()
            current_issue = None
            current_milestone = m.group(1)
            milestones.setdefault(current_milestone, [])
            continue
        h = ISSUE_HEADING.match(line)
        if h:
            flush()
            current_issue = h.group(1)
            if current_issue in issues:
                issues[current_issue + "@DUPLICATE"] = "duplicate heading"
            milestones.setdefault(current_milestone, []).append(current_issue)
            body = [line]
            continue
        if current_issue is not None:
            body.append(line)
    flush()
    # Normalize: duplicates produce @DUPLICATE sentinels — report them.
    duplicates = sorted(k.split("@")[0] for k in issues if k.endswith("@DUPLICATE"))
    clean_issues = {k: v for k, v in issues.items() if "@" not in k}
    return {"issues": clean_issues, "milestones": milestones, "duplicates": duplicates}


def expand_dependencies(body: str, milestones: dict[str, list[str]]) -> tuple[set[str], list[str]]:
    """Resolve an issue's declared dependencies to concrete issue ids.

    `P n-n` tokens resolve directly; `M n`/`M n-m` tokens expand to that
    milestone range's registered issues. Unknown ids return as errors.
    """
    deps: set[str] = set()
    errors: list[str] = []
    dep_lines = DEPENDENCIES_LINE.findall(body)
    dep_text = " ".join(dep_lines)
    for milestone_match in MILESTONE_TOKEN.finditer(dep_text):
        lo = int(milestone_match.group(1))
        hi = int(milestone_match.group(2)) if milestone_match.group(2) else lo
        for num in range(lo, hi + 1):
            label = f"M{num}"
            if label in milestones:
                deps.update(milestones[label])
            else:
                errors.append(f"milestone {label} has no registered issues")
    # Strip milestone tokens before scanning P-tokens so "M0-M10" isn't misread.
    stripped = MILESTONE_TOKEN.sub(" ", dep_text)
    deps.update(f"P{g[0]}-{g[1]}" for g in ISSUE_TOKEN.findall(stripped))
    return deps, errors


def flow_order(register_path: Path, milestones: dict[str, list[str]]) -> list[str]:
    """Total implementation order: the explicit Flow-order chain, then
    M3+ milestones in register order (which is their dependency order)."""
    text = load_text(register_path)
    order: list[str] = []
    m = re.search(r"## Flow order\s+.*?`([^`]+)`", text, re.DOTALL)
    if m:
        order += ISSUE_TOKEN.findall(m.group(1))
    order = [f"P{a}-{b}" for a, b in order]
    for label in sorted(milestones, key=lambda x: int(x[1:]) if x[1:].isdigit() else 99):
        if label in ("M0", "M1", "M2", "UNNUMBERED"):
            continue
        for issue in milestones[label]:
            if issue not in order:
                order.append(issue)
    # Any issue not covered (e.g. inside the chain milestones) — append in
    # register order so the map is total.
    for label, ids in milestones.items():
        for issue in ids:
            if issue not in order:
                order.append(issue)
    return order


def dependency_graph(register: dict[str, Any], order_index: dict[str, int]) -> tuple[
        dict[str, set[str]], list[str], list[str]]:
    """Backward edges (dep earlier in flow order) form the checked graph.
    Forward references — e.g. P0-4 fixtures 'owned by' later feature issues —
    are recorded as warnings, not treated as blocking dependencies."""
    issues = register["issues"]
    milestones = register["milestones"]
    graph: dict[str, set[str]] = {}
    errors: list[str] = []
    forward_refs: list[str] = []
    for issue_id, body in issues.items():
        deps, dep_errors = expand_dependencies(body, milestones)
        errors.extend(f"{issue_id}: {e}" for e in dep_errors)
        for dep in sorted(deps):
            if dep == issue_id:
                continue  # self-reference from own milestone expansion — not a cycle
            if dep not in issues:
                errors.append(f"{issue_id}: dangling dependency {dep}")
                continue
            if order_index.get(dep, -1) > order_index.get(issue_id, 0):
                forward_refs.append(f"{issue_id} -> {dep} (forward ownership/fixture reference)")
                continue
            graph.setdefault(issue_id, set()).add(dep)
    # Set-sourced sequences must serialize deterministically: sorted order keeps
    # report bytes identical across runs regardless of hash-seed iteration order.
    return graph, errors, sorted(forward_refs)


def find_cycles(graph: dict[str, set[str]]) -> list[list[str]]:
    cycles: list[list[str]] = []
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {node: WHITE for node in graph}

    def visit(node: str, stack: list[str]) -> None:
        color[node] = GRAY
        stack.append(node)
        for nxt in sorted(graph.get(node, ())):
            if color.get(nxt, WHITE) == GRAY:
                cycles.append(stack[stack.index(nxt):] + [nxt])
            elif color.get(nxt, WHITE) == WHITE:
                visit(nxt, stack)
        stack.pop()
        color[node] = BLACK

    for node in sorted(graph):
        if color[node] == WHITE:
            visit(node, [])
    return cycles


def issue_status(register: dict[str, Any], docs_dir: Path) -> dict[str, str]:
    """Prefer register status, then issue-specific progress, then phase status."""
    statuses: dict[str, str] = {}
    progress_docs = {p.name.upper(): p.name for p in sorted(docs_dir.glob("*.md"))}
    for issue_id, body in register["issues"].items():
        match = STATUS_LINE.search(body)
        if match:
            statuses[issue_id] = match.group(1)
            continue
        phase = issue_id.split("-")[0]
        exact_doc = (progress_docs.get(issue_id + "-PROGRESS.MD")
                     or progress_docs.get(issue_id + "-CLOSEOUT.MD"))
        doc = exact_doc or progress_docs.get(phase + "-PROGRESS.MD") or progress_docs.get(phase + "-CLOSEOUT.MD")
        if not doc:
            statuses[issue_id] = "NO-LOCAL-STATUS"
            continue
        text = load_text(docs_dir / doc)
        section = re.search(
            rf"^## {re.escape(issue_id)}(?:\s|$)[^\n]*\n(.*?)(?=^## |\Z)",
            text, re.MULTILINE | re.DOTALL)
        match = STATUS_LINE.search(section.group(1)) if section else None
        match = match or STATUS_LINE.search(text)
        statuses[issue_id] = match.group(1) if match else "IN-REVIEW"
    return statuses


def fixture_evidence_checks(root: Path, expected_fixture_count: int) -> tuple[dict[str, Any], dict[str, Any]]:
    report_path = root / "docs" / "parity" / "reports" / "storynpcs-fixture-report.json"
    if not report_path.exists():
        missing = "fresh storynpcs-fixture-report.json is missing"
        return (
            {"pass": False, "blocked": True, "findings": [missing]},
            {"pass": False, "blocked": True, "findings": [missing]},
        )

    report = load_json(report_path)
    fixture_report = report.get("fixture_report")
    if not isinstance(fixture_report, dict):
        return (
            {"pass": False, "blocked": False, "findings": ["fixture report has no validated report payload"]},
            {"pass": False, "blocked": True, "findings": ["target runtime evidence is unavailable"]},
        )
    evidence = fixture_report.get("evidence", {})
    counts = report.get("storynpcs_fixture_counts", {})
    if not isinstance(evidence, dict) or not isinstance(counts, dict):
        return (
            {"pass": False, "blocked": False, "findings": ["fixture report has malformed evidence fields"]},
            {"pass": False, "blocked": True, "findings": ["target runtime evidence is unavailable"]},
        )
    current_fingerprint = source_fingerprint(root)
    fingerprint_matches = report.get("source_fingerprint") == current_fingerprint
    findings = []
    if not fingerprint_matches:
        findings.append("fixture evidence does not match the current source fingerprint")
    if report.get("gradle_exit_code") != 0:
        findings.append("the recorded full Gradle test run did not pass")
    if report.get("status") == "FAIL":
        findings.append("the fixture runner recorded a failed run")
    if report.get("ci_validation_status") != "PASS":
        findings.append("mapped JUnit fixture validation did not pass")
    if report.get("missing_test_selectors") != []:
        findings.append("one or more mapped JUnit selectors were missing")
    if fixture_report.get("validation_status") != "PASS":
        findings.append("fixture catalog validation did not pass")
    if counts.get("fixture_count") != expected_fixture_count:
        findings.append("fixture evidence count does not match the current catalog")
    if counts.get("observed") != expected_fixture_count:
        findings.append("not every fixture has a current StoryNPCs observation")
    if counts.get("blocked") != 0 or counts.get("blocked_mapped") != 0:
        findings.append("fixture execution contains blocked mapped cases")
    if counts.get("failed") != 0 or counts.get("unmapped") != 0:
        findings.append("fixture execution contains failed or unmapped cases")
    probe_fixtures = report.get("storynpcs_probe_report", {}).get("fixtures", [])
    if len(probe_fixtures) != expected_fixture_count:
        findings.append("fixture probe report does not cover the current catalog")
    elif any(
        item.get("storynpcs_probe", {}).get("status") != "OBSERVED"
        or item.get("storynpcs_probe", {}).get("result", {}).get("outcome") != "PASSED"
        for item in probe_fixtures
    ):
        findings.append("one or more current fixture probes lack passing JUnit outcomes")

    current = not findings
    parity_status = evidence.get("parity_status")
    certification_eligible = evidence.get("certification_eligible") is True
    target_verified = parity_status == "VERIFIED" and certification_eligible
    runtime_blocked = not target_verified
    imported = sum(
        1
        for row in evidence.get("fixtures", [])
        if isinstance(row, dict) and row.get("evidence_state") == "VERIFIED_TARGET_RUNTIME"
    )
    runtime_findings: list[str] = []
    if not target_verified:
        if imported:
            runtime_findings.append(
                f"{imported} fixture(s) carry imported VERIFIED_TARGET_RUNTIME observations; "
                "no fixture reaches VERIFIED_PARITY, so target parity remains BLOCKED"
            )
        else:
            runtime_findings.append(
                "target-runtime observations are unavailable; target parity remains BLOCKED"
            )
    return (
        {
            "pass": current,
            "blocked": not current and not fingerprint_matches,
            "findings": findings,
            "detail": {
                "source_fingerprint_matches": fingerprint_matches,
                "junit_test_case_count": report.get("junit_test_case_count"),
                "storynpcs_fixture_counts": counts,
            },
        },
        {
            "pass": certification_eligible and parity_status == "VERIFIED",
            "blocked": runtime_blocked,
            "findings": runtime_findings,
            "detail": {
                "parity_status": parity_status,
                "imported_target_observation_count": imported,
            },
        },
    )


def run_gate(root: Path) -> dict[str, Any]:
    parity = root / "docs" / "parity"
    checks: dict[str, dict[str, Any]] = {}

    register_path = root / "docs" / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md"
    if not register_path.exists():
        register_path = root / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md"
    register = parse_register(register_path)
    order_index = {issue: i for i, issue in enumerate(
        flow_order(register_path, register["milestones"]))}
    checks["issue_ids_unique"] = {
        "pass": not register["duplicates"],
        "findings": [f"duplicate issue id {d}" for d in register["duplicates"]],
        "detail": f"{len(register['issues'])} issues across {len(register['milestones'])} milestones",
    }

    graph, dep_errors, forward_refs = dependency_graph(register, order_index)
    cycles = find_cycles(graph)
    checks["dependency_graph"] = {
        "pass": not dep_errors and not cycles,
        "findings": dep_errors + ["dependency cycle: " + " -> ".join(c) for c in cycles],
        "detail": {"backward_edges": sum(len(v) for v in graph.values()),
                   "forward_references": forward_refs},
    }

    statuses = issue_status(register, parity)
    non_terminal = {k: v for k, v in statuses.items()
                    if v not in DONE_STATES | BLOCKED_STATES}
    unknown_statuses = {
        issue: status for issue, status in non_terminal.items()
        if status not in UNFINISHED_STATES
    }
    checks["issue_statuses_terminal"] = {
        "pass": not non_terminal,
        "blocked": bool(non_terminal) and not unknown_statuses,
        "findings": [f"{k}: {v}" for k, v in sorted(non_terminal.items())],
        "detail": {s: sum(1 for v in statuses.values() if v == s)
                   for s in sorted(set(statuses.values()))},
        "interpretation": "IN-REVIEW and unaccepted BLOCKED issues are not terminal; "
                          "only completed or user-accepted blocked issues satisfy release closure.",
    }
    p11_3_status = statuses.get("P11-3", "NO-LOCAL-STATUS")
    checks["p11_3_status"] = {
        "pass": p11_3_status == "BLOCKED",
        "blocked": p11_3_status == "BLOCKED",
        "findings": [] if p11_3_status == "BLOCKED" else [
            f"P11-3 must remain BLOCKED until runtime and evidence gates pass; found {p11_3_status}"
        ],
        "detail": p11_3_status,
    }

    manifest = load_json(parity / "target-surface-manifest.json")
    catalog = load_json(parity / "fixture-catalog.json")
    test_map = catalog.get("storynpcs_test_map", {})
    fixture_rows = catalog.get("fixtures", [])
    fixture_ids = {f["fixture_id"] for f in fixture_rows}
    fixture_execution, target_runtime = fixture_evidence_checks(root, len(fixture_rows))
    checks["storynpcs_fixture_execution"] = fixture_execution
    checks["target_runtime_evidence"] = target_runtime
    op_findings = []
    if len(fixture_ids) != len(fixture_rows):
        op_findings.append("fixture catalog contains duplicate fixture IDs")
    for row in manifest["surfaces"].get("operation_matrix", []):
        if not row.get("closing_issue_ids"):
            op_findings.append(f"operation {row.get('id')} names no closing issues")
        for fid in row.get("parity_fixture_ids", []):
            if fid not in fixture_ids:
                op_findings.append(f"operation {row.get('id')} references unknown fixture {fid}")
            elif not test_map.get(fid):
                op_findings.append(f"operation {row.get('id')} fixture {fid} has no JUnit selectors")
    if len(manifest["surfaces"].get("operation_matrix", [])) != 15:
        op_findings.append("operation_matrix does not contain exactly 15 operations")
    checks["fifteen_operations_evidence"] = {
        "pass": not op_findings, "findings": op_findings,
        "detail": "every operation row needs closing issues + a selector-mapped fixture",
    }

    compat_path = parity / "reports" / "compatibility-report.json"
    if compat_path.exists():
        compat = load_json(compat_path)
        by_state = compat["summary"].get("by_state", {})
        compat_findings = []
        if compat.get("validation_status") != "PASS":
            compat_findings += compat.get("errors", [])[:10]
        if compat["summary"].get("unmapped_rows", 0):
            compat_findings.append(f"{compat['summary']['unmapped_rows']} unmapped rows")
        if by_state.get("UNKNOWN"):
            compat_findings.append(f"{by_state['UNKNOWN']} UNKNOWN rows remain")
        domains = catalog.get("required_domains", [])
        manifest_domains = manifest.get("fixture_coverage", {}).get("domains", [])
        missing = sorted(set(domains) - set(manifest_domains))
        if missing:
            compat_findings.append(f"domains missing from manifest fixture coverage: {missing}")
    else:
        compat_findings = ["compatibility-report.json missing — run tools/parity/compatibility_report.py"]
        compat = {}
    checks["twentytwo_domains_mapped"] = {
        "pass": not compat_findings,
        "findings": compat_findings,
        "detail": compat.get("summary", {}).get("by_state", {}),
    }

    bench_findings = []
    bench_detail = {}
    for scenario in REQUIRED_BENCHMARKS:
        path = parity / "reports" / f"benchmark-{scenario}.json"
        if not path.exists():
            bench_findings.append(f"benchmark-{scenario}.json missing")
            continue
        artifact = load_json(path)
        runs = artifact.get("runs", [])
        if not runs:
            bench_findings.append(f"{scenario}: no recorded runs")
        failed = [c for run in runs for c in run.get("threshold_results", [])
                  if not c.get("pass")]
        if failed:
            bench_findings.append(f"{scenario}: {len(failed)} failed threshold checks")
        if artifact.get("certification_state") != "HEADLESS_PASS_LIVE_RUNTIME_UNVERIFIED":
            bench_findings.append(f"{scenario}: unexpected certification_state "
                                  f"{artifact.get('certification_state')!r}")
        bench_detail[scenario] = {
            "runs": len(runs),
            "certification_state": artifact.get("certification_state"),
            "timing_repeatable_within_10pct": artifact.get("timing_repeatable_within_10pct"),
        }
    checks["benchmark_artifacts"] = {
        "pass": not bench_findings, "findings": bench_findings, "detail": bench_detail,
    }

    hard_failures = [name for name, check in checks.items()
                     if not check["pass"] and not check.get("blocked", False)]
    blocked_checks = [name for name, check in checks.items() if check.get("blocked", False)]
    gate_status = "FAIL" if hard_failures else "BLOCKED" if blocked_checks else "PASS"
    return {"gate_status": gate_status,
            "honesty": "BLOCKED means required evidence is unavailable; target-runtime parity "
                       "cannot be certified by JVM tests or headless GameTests.",
            "blocking_checks": blocked_checks,
            "failed_checks": hard_failures,
            "checks": checks}


def write_report(report: dict[str, Any], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # newline="\n" keeps generated bytes LF on every platform — os.linesep
    # translation would otherwise reintroduce CRLF on Windows checkouts (#127).
    out_path.write_text(
        json.dumps(report, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    report = run_gate(root)
    out = root / "docs" / "parity" / "reports" / "release-gate-report.json"
    write_report(report, out)
    print(f"gate={report['gate_status']} -> {out}")
    for name, check in report["checks"].items():
        status = "BLOCKED" if check.get("blocked") else "PASS" if check["pass"] else "FAIL"
        print(f"  {status} {name} {check['findings'][:3]}")
    return 0 if report["gate_status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
