#!/usr/bin/env python3
"""Generate a deterministic, source-free CustomNPCs target surface manifest.

The JAR is the identity authority. The research inventories provide reviewed
static surface records; this tool copies only metadata (symbols, paths,
counts, and evidence labels), never decompiled implementation text.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Any, Iterable


TARGET_NAME = "CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar"
TARGET_SHA256 = "6c28d87b215fc1191488194188ec8a39dd908ae7d2d887b7c9d7d463be0a162c"
TARGET_SHA1 = "e2f3b58ceb5aac4021d7bfd130e320925581471b"
PINNED_RESEARCH_COMMIT = "9f7a921a5c3fbc66199b0d7719bdc0c56081745d"
MAX_JSON_INPUT_BYTES = 50 * 1024 * 1024
MAX_RECORD_BYTES = 64 * 1024
MAX_OUTPUT_BYTES = 25 * 1024 * 1024
MAX_FIELD_LENGTH = 4096

EXPECTED_COUNTS = {
    "archive_entries": 2467,
    "classes": 1230,
    "assets": 972,
    "data": 49,
    "gui": 149,
    "packets": 155,
    "commands": 70,
    "events": 97,
    "roles": 7,
    "jobs": 11,
    "companion_jobs": 3,
    "persistence_stores": 18,
    "persistence_participants": 60,
    "operations": 15,
}

SURFACE_ISSUES = {
    "target_classes": ["P0-1"],
    "assets": ["P3-1", "P8-3"],
    "data": ["P2-1"],
    "gui": ["P10-1"],
    "packets": ["P1-3"],
    "commands": ["P9-3"],
    "events": ["P9-1"],
    "roles": ["P6-2", "P6-3", "P6-5", "P7-1", "P7-2"],
    "jobs": ["P6-4"],
    "companion_jobs": ["P6-5"],
    "persistence_participants": ["P2-2"],
    "persistence_stores": ["P2-2"],
    "operation_matrix": ["P0-4"],
}

OPERATION_CLOSING_ISSUES = {
    "NPC-MODULE-MUTATION": ["P1-1", "P1-2", "P3-1", "P3-2", "P3-3", "P3-4"],
    "DIALOG-DEFINITION": ["P5-1", "P5-3"],
    "DIALOG-PLAYER-CHOICE": ["P5-2"],
    "FACTION-PROGRESSION": ["P6-1"],
    "QUEST-DEFINITION": ["P5-4"],
    "QUEST-COMPLETION": ["P1-1", "P5-5", "P2-3"],
    "ROLE-JOB": ["P6-2", "P6-3", "P6-4", "P6-5"],
    "INVENTORY-EQUIPMENT": ["P3-4", "P2-3"],
    "TRADE": ["P7-1", "P2-3"],
    "BANK": ["P7-2", "P2-3"],
    "TRANSPORT": ["P6-3", "P2-3"],
    "SPAWN-CLONE": ["P8-1", "P2-1", "P2-3"],
    "MARK": ["P3-4"],
    "SCRIPT": ["P9-1", "P9-2", "P9-3"],
    "CONFIG-WORLD-TOOLS": ["P8-2", "P8-3", "P8-4", "P8-5", "P8-6", "P9-4"],
}

ROLE_ISSUES = {
    "noppes.npcs.roles.RoleBank": ["P7-2"],
    "noppes.npcs.roles.RoleCompanion": ["P6-5"],
    "noppes.npcs.roles.RoleDialog": ["P6-2"],
    "noppes.npcs.roles.RoleFollower": ["P6-2", "P6-5"],
    "noppes.npcs.roles.RolePostman": ["P6-2"],
    "noppes.npcs.roles.RoleTrader": ["P7-1"],
    "noppes.npcs.roles.RoleTransporter": ["P6-3"],
}

SURFACE_METADATA = {
    "target_classes": ("target-inventory", "CROSS-CUTTING"),
    "assets": ("display-and-world-assets", "CROSS-CUTTING"),
    "data": ("definition-and-world-data", "CROSS-CUTTING"),
    "gui": ("authoring-ui", "CROSS-CUTTING"),
    "packets": ("networking", "CROSS-CUTTING"),
    "commands": ("commands", "CONFIG-WORLD-TOOLS"),
    "events": ("public-events", "CROSS-CUTTING"),
    "roles": ("roles", "ROLE-JOB"),
    "jobs": ("jobs", "ROLE-JOB"),
    "companion_jobs": ("companions", "ROLE-JOB"),
    "persistence_participants": ("persistence", "CROSS-CUTTING"),
    "persistence_stores": ("persistence", "CROSS-CUTTING"),
    "operation_matrix": ("operation-contract", "CROSS-CUTTING"),
}

SURFACE_COUNT_KEYS = {
    "target_classes": "classes",
    "assets": "assets",
    "data": "data",
    "gui": "gui",
    "packets": "packets",
    "commands": "commands",
    "events": "events",
    "roles": "roles",
    "jobs": "jobs",
    "companion_jobs": "companion_jobs",
    "persistence_participants": "persistence_participants",
    "persistence_stores": "persistence_stores",
    "operation_matrix": "operations",
}

SURFACE_PROVENANCE = {
    "target_classes": "jar-class",
    "assets": "jar-entry",
    "data": "jar-entry",
    "gui": "decompiled-source",
    "packets": "decompiled-source",
    "commands": "decompiled-source",
    "events": "jar-class",
    "roles": "jar-class",
    "jobs": "jar-class",
    "companion_jobs": "jar-class",
    "persistence_participants": "decompiled-source",
    "persistence_stores": "decompiled-source",
    "operation_matrix": "research-inventory",
}

SOURCE_REQUIRED_SURFACES = {"gui", "packets", "commands", "persistence_participants"}


def load_json(path: Path) -> dict[str, Any]:
    if path.stat().st_size > MAX_JSON_INPUT_BYTES:
        raise ValueError(f"JSON input exceeds {MAX_JSON_INPUT_BYTES} bytes: {path}")
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def verify_all_research_target_hashes(inventories_root: Path) -> None:
    """Reject stale target identities in every research inventory document."""
    for path in sorted(inventories_root.rglob("*.json")):
        if not path.is_file():
            continue
        if path.stat().st_size > MAX_JSON_INPUT_BYTES:
            raise ValueError(f"JSON input exceeds {MAX_JSON_INPUT_BYTES} bytes: {path}")
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle)

        def visit(node: Any) -> Iterable[str]:
            if isinstance(node, dict):
                for key, child in node.items():
                    if "target_sha256" in str(key):
                        yield str(child)
                    yield from visit(child)
            elif isinstance(node, list):
                for child in node:
                    yield from visit(child)

        for target_hash in visit(value):
            if target_hash != TARGET_SHA256:
                raise ValueError(f"research inventory target hash drift: {path.name}")


def digest(path: Path, algorithm: str) -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def rooted_path(root: Path, relative: str, label: str) -> Path:
    root_path = root.resolve()
    candidate = (root_path / relative).resolve()
    if candidate != root_path and root_path not in candidate.parents:
        raise ValueError(f"{label} escapes its configured root: {relative}")
    return candidate


def jar_inventory(path: Path) -> tuple[list[str], str, str]:
    names: list[str] = []
    with zipfile.ZipFile(path) as archive:
        bad_entry = archive.testzip()
        if bad_entry is not None:
            raise ValueError(f"archive CRC check failed: {bad_entry}")
        names = sorted(info.filename for info in archive.infolist())
    return names, digest(path, "sha256"), digest(path, "sha1")


def class_names(entries: list[str]) -> list[str]:
    return [entry[:-6].replace("/", ".") for entry in entries if entry.endswith(".class")]


def package_classes(packages: dict[str, Any], package_name: str) -> list[str]:
    for package in packages.get("packages", []):
        if package.get("package") == package_name:
            return sorted(str(value) for value in package.get("classes", []))
    return []


def issue_ids_from_documents(paths: Iterable[Path]) -> set[str]:
    pattern = re.compile(r"^###\s+(P\d+-\d+)\b", re.MULTILINE)
    result: set[str] = set()
    for path in paths:
        result.update(pattern.findall(path.read_text(encoding="utf-8")))
    return result


def record(
    surface: str,
    index: int,
    symbol: str,
    issue_ids: list[str],
    evidence_state: str = "VERIFIED_TARGET_SOURCE",
    **details: Any,
) -> dict[str, Any]:
    domain, operation_family = SURFACE_METADATA[surface]
    value: dict[str, Any] = {
        "symbol": symbol,
        "domain": domain,
        "operation_family": operation_family,
        "surface": surface,
        "inventory_id": f"target.{surface}.{index:04d}",
        "issue_ids": sorted(issue_ids),
        "evidence_state": evidence_state,
    }
    value.update({key: details[key] for key in sorted(details) if details[key] is not None})
    if len(json.dumps(value, sort_keys=True)) > MAX_RECORD_BYTES:
        raise ValueError(f"record exceeds {MAX_RECORD_BYTES} bytes: {surface}/{symbol}")
    return value


def compact_records(
    surface: str,
    records: list[dict[str, Any]],
    fields: tuple[str, ...],
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, raw in enumerate(records, start=1):
        if surface in SOURCE_REQUIRED_SURFACES and not raw.get("source"):
            raise ValueError(f"missing source provenance for {surface} record {index}")
        path_symbol = "/".join(str(part) for part in raw.get("path", []))
        symbol = str(
            path_symbol
            if surface == "commands" and path_symbol
            else raw.get("class_name")
            or raw.get("message_class")
            or raw.get("payload_id")
            or raw.get("source")
            or raw.get("id")
            or path_symbol
        )
        details = {field: raw[field] for field in fields if field in raw}
        if "source" in details:
            details["provenance"] = "decompiled-source"
        result.append(
            record(
                surface,
                index,
                symbol,
                SURFACE_ISSUES[surface],
                evidence_state=str(raw.get("evidence", "VERIFIED_TARGET_SOURCE")),
                **details,
            )
        )
    return sorted(result, key=lambda item: item["symbol"])


def symbol_records(surface: str, symbols: Iterable[str]) -> list[dict[str, Any]]:
    return [
        record(
            surface,
            index,
            symbol,
            ROLE_ISSUES.get(symbol, SURFACE_ISSUES[surface]) if surface == "roles" else SURFACE_ISSUES[surface],
            source=symbol.replace(".", "/") + ".class",
            provenance="jar-class",
        )
        for index, symbol in enumerate(sorted(symbols), start=1)
    ]


def fixture_coverage(catalog: dict[str, Any]) -> tuple[dict[str, dict[str, list[str]]], set[str]]:
    fixtures = catalog.get("fixtures")
    domains = catalog.get("required_domains")
    operations = catalog.get("required_operation_families")
    if not isinstance(fixtures, list) or not isinstance(domains, list) or not isinstance(operations, list):
        raise ValueError("fixture catalog must declare fixtures, required_domains, and required_operation_families")

    fixture_ids: set[str] = set()
    by_domain = {domain: [] for domain in domains}
    by_operation = {operation: [] for operation in operations}
    if len(by_domain) != len(domains) or len(by_operation) != len(operations):
        raise ValueError("fixture catalog domain/operation lists contain duplicates")
    if len(by_domain) != 22 or len(by_operation) != 15:
        raise ValueError("fixture catalog must preserve the 22-domain/15-operation contract")
    for fixture in fixtures:
        if not isinstance(fixture, dict):
            raise ValueError("fixture catalog contains a non-object fixture")
        fixture_id = fixture.get("fixture_id")
        if not isinstance(fixture_id, str) or not fixture_id:
            raise ValueError("fixture catalog contains an invalid fixture_id")
        if fixture_id in fixture_ids:
            raise ValueError(f"duplicate fixture_id in catalog: {fixture_id}")
        fixture_ids.add(fixture_id)
        domain = fixture.get("domain")
        operation = fixture.get("operation_family")
        if domain not in by_domain or operation not in by_operation:
            raise ValueError(f"fixture has unknown domain/operation: {fixture_id}")
        by_domain[domain].append(fixture_id)
        by_operation[operation].append(fixture_id)

    missing_domains = sorted(domain for domain, ids in by_domain.items() if not ids)
    missing_operations = sorted(operation for operation, ids in by_operation.items() if not ids)
    if missing_domains or missing_operations:
        raise ValueError(f"fixture catalog has uncovered domains/operations: {missing_domains}; {missing_operations}")
    return {
        "domains": {key: sorted(value) for key, value in sorted(by_domain.items())},
        "operation_families": {key: sorted(value) for key, value in sorted(by_operation.items())},
    }, fixture_ids


def attach_fixture_references(
    surfaces: dict[str, list[dict[str, Any]]],
    coverage: dict[str, dict[str, list[str]]],
    catalog: dict[str, Any],
) -> None:
    fixtures = catalog["fixtures"]
    issue_to_fixture_ids: dict[str, set[str]] = {}
    for fixture in fixtures:
        for issue_id in fixture.get("issue_ids", []):
            issue_to_fixture_ids.setdefault(issue_id, set()).add(fixture["fixture_id"])

    for surface_name, records in surfaces.items():
        for item in records:
            if surface_name == "operation_matrix":
                operation = item["id"]
                item["closing_issue_ids"] = OPERATION_CLOSING_ISSUES[operation]
                refs = coverage["operation_families"][operation]
            elif surface_name == "roles":
                refs = sorted(set().union(*(issue_to_fixture_ids.get(issue, set()) for issue in item["issue_ids"])))
            else:
                refs = coverage["domains"].get(item["domain"], [])
                if not refs:
                    refs = sorted(set().union(*(issue_to_fixture_ids.get(issue, set()) for issue in item["issue_ids"])))
            item["parity_fixture_ids"] = sorted(set(refs))
            item["coverage_status"] = "FIXTURE_REFERENCED" if item["parity_fixture_ids"] else "INVENTORY_ONLY"


def entry_records(surface: str, entries: Iterable[str]) -> list[dict[str, Any]]:
    return [
        record(
            surface,
            index,
            entry,
            SURFACE_ISSUES[surface],
            source=entry,
            provenance="jar-entry",
        )
        for index, entry in enumerate(sorted(entries), start=1)
    ]


def persistence_store_records(stores: list[dict[str, Any]], decompiled_root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, store in enumerate(stores, start=1):
        source_files = [str(value) for value in store.get("source_files", [])]
        if not source_files or len(source_files) != len(set(source_files)):
            raise ValueError(f"persistence store has no source file: {store.get('id')}")
        source = source_files[0]
        source_hashes: dict[str, str] = {}
        for source_file in source_files:
            if not source_file.endswith(".java"):
                raise ValueError(f"invalid persistence store Java source: {source_file}")
            source_path = rooted_path(decompiled_root, source_file, "persistence source")
            if not source_path.is_file():
                raise ValueError(f"persistence store source missing: {source_file}")
            source_hashes[source_file] = digest(source_path, "sha256")
        result.append(
            record(
                "persistence_stores",
                index,
                str(store["id"]),
                SURFACE_ISSUES["persistence_stores"],
                source=source,
                source_files=source_files,
                source_sha256=source_hashes[source],
                source_sha256_by_file=source_hashes,
                provenance="decompiled-source",
                owner=str(store.get("owner", "")),
                format=str(store.get("format", "")),
            )
        )
    return sorted(result, key=lambda item: item["symbol"])


def attach_decompiled_source_hashes(
    records: list[dict[str, Any]], decompiled_root: Path
) -> None:
    for item in records:
        source = item.get("source")
        if not isinstance(source, str) or not source.endswith(".java"):
            raise ValueError(f"invalid decompiled source path: {source!r}")
        source_path = rooted_path(decompiled_root, source, "decompiled source")
        if not source_path.is_file():
            raise ValueError(f"decompiled source missing: {source}")
        source_hash = digest(source_path, "sha256")
        recorded_hash = item.get("source_sha256")
        if recorded_hash is not None and recorded_hash != source_hash:
            raise ValueError(f"decompiled source hash drift: {source}")
        item["source_sha256"] = source_hash


def verify_research_metadata(
    artifact: dict[str, Any],
    gui: dict[str, Any],
    packets: dict[str, Any],
    commands: dict[str, Any],
    roles_jobs: dict[str, Any],
    persistence: dict[str, Any],
    persistence_schema: dict[str, Any],
    parity: dict[str, Any],
) -> None:
    if artifact.get("sha256") != TARGET_SHA256:
        raise ValueError("research artifact SHA-256 does not match target")
    if artifact.get("entry_count") != EXPECTED_COUNTS["archive_entries"]:
        raise ValueError("research artifact entry count drift")
    if artifact.get("class_count") != EXPECTED_COUNTS["classes"]:
        raise ValueError("research artifact class count drift")
    inventories = {
        "GUI": gui,
        "packet": packets,
        "command": commands,
        "role/job": roles_jobs,
        "persistence participant": persistence,
        "persistence": persistence_schema,
        "parity": parity,
    }
    for label, inventory in inventories.items():
        if inventory.get("target_sha256") != TARGET_SHA256:
            raise ValueError(f"{label} inventory target hash drift")
    checks = {
        "gui": gui.get("counts", {}).get("gui_classes"),
        "packets": packets.get("packet_count"),
        "commands": commands.get("leaf_count"),
        "roles": roles_jobs.get("full_role_job_inventory", {}).get("concrete_roles"),
        "jobs": roles_jobs.get("full_role_job_inventory", {}).get("concrete_jobs"),
        "companion_jobs": roles_jobs.get("full_role_job_inventory", {}).get("companion_jobs"),
        "persistence_stores": persistence_schema.get("counts", {}).get("catalog_stores"),
        "operations": parity.get("counts", {}).get("operations"),
    }
    for key, actual in checks.items():
        if actual != EXPECTED_COUNTS[key]:
            raise ValueError(f"research inventory drift for {key}: {actual}")


def verify_pinned_research_inputs(research_root: Path) -> None:
    """Require the reviewed source inventories from the exact clean research revision."""
    try:
        revision = subprocess.run(
            ["git", "-C", str(research_root), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=False,
        )
        if revision.returncode != 0:
            raise ValueError("research root is not a readable Git repository")
        actual_revision = revision.stdout.strip()
        if actual_revision != PINNED_RESEARCH_COMMIT:
            raise ValueError(
                f"research repository revision drift: expected {PINNED_RESEARCH_COMMIT}, "
                f"got {actual_revision or '<empty>'}"
            )
        status = subprocess.run(
            [
                "git", "-C", str(research_root), "status", "--porcelain",
                "--untracked-files=all", "--", "inventories",
                "generated/jar/artifact.json", "generated/jar/packages.json",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        raise ValueError(f"could not verify pinned research repository: {error}") from error
    if status.returncode != 0:
        raise ValueError("could not inspect pinned research inventory status")
    if status.stdout.strip():
        raise ValueError("pinned research inventory inputs have local changes")


def source_record_key(surface: str, item: dict[str, Any]) -> tuple[Any, ...]:
    if surface == "gui" or surface == "persistence_participants":
        fields = ("class_name",)
    elif surface == "packets":
        fields = ("payload_id", "message_class", "direction")
    elif surface == "commands":
        path = item.get("path")
        if not isinstance(path, list) or not path or not all(
            isinstance(part, str) and part for part in path
        ):
            raise ValueError("source mapping drift: command path is missing or invalid")
        return tuple(path)
    elif surface == "persistence_stores":
        value = item.get("id", item.get("symbol"))
        if not isinstance(value, str) or not value:
            raise ValueError("source mapping drift: persistence store identity is missing or invalid")
        return (value,)
    else:
        raise ValueError(f"unsupported source-mapped surface: {surface}")
    values = tuple(item.get(field) for field in fields)
    if any(not isinstance(value, str) or not value for value in values):
        raise ValueError(f"source mapping drift: {surface} identity is missing or invalid")
    return values


def verify_source_record_bindings(manifest: dict[str, Any], research_root: Path) -> None:
    """Bind manifest source paths to the named rows in the pinned research inventories."""
    inventory_specs = {
        "gui": (
            "all_gui_fields.json", "records", ("class_name", "source", "source_sha256"),
        ),
        "packets": (
            "packets.json", "records",
            ("payload_id", "message_class", "direction", "source"),
        ),
        "commands": ("command_tree.json", "leaves", ("path", "source", "operation")),
        "persistence_participants": (
            "persistence.json", "records", ("class_name", "source", "role"),
        ),
        "persistence_stores": (
            "persistence_schema.json", "stores", ("owner", "format", "source_files"),
        ),
    }
    surfaces = manifest.get("surfaces", {})
    inventories_root = research_root / "inventories"
    for surface, (filename, records_key, compared_fields) in inventory_specs.items():
        manifest_records = surfaces.get(surface, [])
        if not manifest_records:
            continue
        inventory = load_json(inventories_root / filename)
        if inventory.get("target_sha256") != TARGET_SHA256:
            raise ValueError(f"{surface} inventory target hash drift")
        source_records = inventory.get(records_key)
        if not isinstance(source_records, list):
            raise ValueError(f"{surface} inventory records must be a list")
        authoritative: dict[tuple[Any, ...], dict[str, Any]] = {}
        for raw in source_records:
            if not isinstance(raw, dict):
                raise ValueError(f"{surface} inventory contains a non-object source record")
            key = source_record_key(surface, raw)
            if key in authoritative:
                raise ValueError(f"duplicate source mapping in {surface}: {key}")
            authoritative[key] = raw
        mapped: dict[tuple[Any, ...], dict[str, Any]] = {}
        for row in manifest_records:
            if not isinstance(row, dict):
                raise ValueError(f"{surface} manifest contains a non-object source record")
            key = source_record_key(surface, row)
            if key in mapped:
                raise ValueError(f"duplicate source mapping in manifest {surface}: {key}")
            mapped[key] = row
        if set(mapped) != set(authoritative):
            missing = sorted(set(authoritative) - set(mapped), key=str)
            extra = sorted(set(mapped) - set(authoritative), key=str)
            raise ValueError(f"source mapping drift in {surface}: missing={missing}, extra={extra}")
        for key, raw in authoritative.items():
            row = mapped[key]
            for field in compared_fields:
                if row.get(field) != raw.get(field):
                    symbol = row.get("symbol", key)
                    raise ValueError(
                        f"source mapping drift in {surface}/{symbol}: {field} disagrees with inventory"
                    )
            if "source_sha256" in raw and row.get("source_sha256") != raw["source_sha256"]:
                raise ValueError(
                    f"source mapping drift in {surface}/{row.get('symbol')}: source hash disagrees with inventory"
                )
            if surface == "gui" and row.get("symbol") != raw.get("class_name"):
                raise ValueError(f"source mapping drift in gui/{row.get('symbol')}: class identity drift")
            if surface == "persistence_participants" and row.get("symbol") != raw.get("class_name"):
                raise ValueError(
                    f"source mapping drift in persistence_participants/{row.get('symbol')}: class identity drift"
                )
            if surface == "packets" and row.get("symbol") != raw.get("message_class"):
                raise ValueError(
                    f"source mapping drift in packets/{row.get('symbol')}: message identity drift"
                )
            if surface == "commands" and row.get("symbol") != "/".join(raw["path"]):
                raise ValueError(f"source mapping drift in commands/{row.get('symbol')}: path identity drift")
            if surface == "persistence_stores" and (
                row.get("symbol") != raw.get("id") or row.get("source") != raw["source_files"][0]
            ):
                raise ValueError(
                    f"source mapping drift in persistence_stores/{row.get('symbol')}: store identity drift"
                )


def verify_source_provenance(
    manifest: dict[str, Any],
    jar_entries: set[str],
    research_root: Path,
    decompiled_root: Path,
) -> None:
    for surface, records in manifest["surfaces"].items():
        if not isinstance(records, list):
            continue
        for item in records:
            source = item.get("source")
            provenance = item.get("provenance")
            if not source or not provenance:
                raise ValueError(f"missing provenance for {surface}/{item.get('symbol')}")
            if provenance == "jar-entry":
                if source not in jar_entries:
                    raise ValueError(f"JAR entry missing: {source}")
            elif provenance == "jar-class":
                if source not in jar_entries:
                    raise ValueError(f"JAR class missing: {source}")
            elif provenance == "decompiled-source":
                sources = item.get("source_files", [source])
                if (
                    not isinstance(sources, list)
                    or not sources
                    or not all(isinstance(value, str) for value in sources)
                    or len(sources) != len(set(sources))
                ):
                    raise ValueError(f"persistence source file list missing or invalid: {item.get('symbol')}")
                if surface == "persistence_stores":
                    hashes = item.get("source_sha256_by_file")
                    if not isinstance(hashes, dict) or set(hashes) != set(sources):
                        raise ValueError(f"persistence source hashes missing or invalid: {item.get('symbol')}")
                    if source != sources[0] or item.get("source_sha256") != hashes.get(source):
                        raise ValueError(f"persistence primary source hash drift: {item.get('symbol')}")
                else:
                    hashes = {source: item.get("source_sha256")}
                    if sources != [source]:
                        raise ValueError(f"unexpected multiple source files for {surface}/{item.get('symbol')}")
                for source_file in sources:
                    if not isinstance(source_file, str) or not source_file.endswith(".java"):
                        raise ValueError(f"invalid decompiled source path: {source_file!r}")
                    class_entry = source_file.removesuffix(".java") + ".class"
                    if class_entry not in jar_entries:
                        raise ValueError(f"{surface} source missing from target JAR: {source_file}")
                    source_path = rooted_path(decompiled_root, source_file, "decompiled source")
                    if not source_path.is_file():
                        raise ValueError(f"decompiled source missing: {source_file}")
                    expected_hash = hashes.get(source_file)
                    if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
                        raise ValueError(f"decompiled source hash missing or invalid: {source_file}")
                    if digest(source_path, "sha256") != expected_hash:
                        raise ValueError(f"decompiled source hash drift: {source_file}")
            elif provenance == "research-inventory":
                source_path = rooted_path(research_root, str(source), "research source")
                if not source_path.is_file():
                    raise ValueError(f"research source missing: {source}")
                expected_hash = item.get("source_sha256")
                if not expected_hash or digest(source_path, "sha256") != expected_hash:
                    raise ValueError(f"research source hash drift: {source}")
            else:
                raise ValueError(f"unknown provenance type: {provenance}")
    if any(
        isinstance(item, dict) and item.get("provenance") == "decompiled-source"
        for records in manifest["surfaces"].values()
        if isinstance(records, list)
        for item in records
    ):
        verify_pinned_research_inputs(research_root)
        verify_source_record_bindings(manifest, research_root)


def build_manifest(jar: Path, research_root: Path, decompiled_root: Path, document_root: Path) -> dict[str, Any]:
    entries, sha256, sha1 = jar_inventory(jar)
    if jar.name != TARGET_NAME:
        raise ValueError(f"unexpected artifact name: {jar.name}")
    if sha256 != TARGET_SHA256 or sha1 != TARGET_SHA1:
        raise ValueError(f"target digest mismatch: sha256={sha256}, sha1={sha1}")

    generated = research_root / "generated" / "jar"
    inventories = research_root / "inventories"
    artifact = load_json(generated / "artifact.json")
    packages = load_json(generated / "packages.json")
    gui = load_json(inventories / "all_gui_fields.json")
    packets = load_json(inventories / "packets.json")
    commands = load_json(inventories / "command_tree.json")
    roles_jobs = load_json(inventories / "role_job_contract.json")
    persistence = load_json(inventories / "persistence.json")
    persistence_schema = load_json(inventories / "persistence_schema.json")
    parity = load_json(inventories / "parity_acceptance_matrix.json")
    issue_surface_claims = load_json(document_root / "parity" / "issue-surface-claims.json")
    verify_all_research_target_hashes(inventories)
    verify_research_metadata(
        artifact, gui, packets, commands, roles_jobs, persistence, persistence_schema, parity
    )

    target_classes = class_names(entries)
    events = package_classes(packages, "noppes.npcs.api.event")
    role_package_classes: list[str] = []
    for package in packages.get("packages", []):
        if str(package.get("package", "")).startswith("noppes.npcs.roles"):
            role_package_classes.extend(str(value) for value in package.get("classes", []))
    role_package_classes = sorted(value for value in role_package_classes if "$" not in value)
    role_classes = sorted(
        value
        for value in role_package_classes
        if value.rsplit(".", 1)[-1].startswith("Role")
        and value.rsplit(".", 1)[-1] != "RoleInterface"
    )
    job_classes = sorted(
        value
        for value in role_package_classes
        if value.rsplit(".", 1)[-1].startswith("Job")
        and value.rsplit(".", 1)[-1] != "JobInterface"
    )
    companion_job_classes = sorted(
        value
        for value in role_package_classes
        if ".companion." in value
        and value.rsplit(".", 1)[-1] not in {"CompanionFoodStats", "CompanionJobInterface"}
    )

    entries_by_category: dict[str, list[str]] = {"asset": [], "data": []}
    for entry in entries:
        if entry.startswith("assets/") and not entry.endswith("/"):
            entries_by_category["asset"].append(entry)
        elif entry.startswith("data/") and not entry.endswith("/"):
            entries_by_category["data"].append(entry)

    gui_records = compact_records(
        "gui", gui.get("records", []), ("class_name", "source", "source_sha256")
    )
    packet_records = compact_records(
        "packets", packets.get("records", []), ("payload_id", "message_class", "direction", "source", "source_sha256")
    )
    command_records = compact_records(
        "commands", commands.get("leaves", []), ("path", "source", "operation", "source_sha256")
    )
    persistence_records = compact_records(
        "persistence_participants", persistence.get("records", []), ("class_name", "source", "role", "source_sha256")
    )
    for source_records in (gui_records, packet_records, command_records, persistence_records):
        attach_decompiled_source_hashes(source_records, decompiled_root)
    persistence_store_records_value = persistence_store_records(
        persistence_schema.get("stores", []), decompiled_root
    )
    operation_records = compact_records(
        "operation_matrix", parity.get("records", []), ("id", "label", "canonical_operation_candidate")
    )
    parity_source = "inventories/parity_acceptance_matrix.json"
    parity_source_hash = digest(research_root / parity_source, "sha256")
    for item in operation_records:
        item["source"] = parity_source
        item["source_sha256"] = parity_source_hash
        item["provenance"] = "research-inventory"

    catalog_path = document_root / "parity" / "fixture-catalog.json"
    fixture_catalog = load_json(catalog_path)
    coverage, known_fixture_ids = fixture_coverage(fixture_catalog)
    surfaces = {
        "target_classes": symbol_records("target_classes", target_classes),
        "assets": entry_records("assets", entries_by_category["asset"]),
        "data": entry_records("data", entries_by_category["data"]),
        "gui": gui_records,
        "packets": packet_records,
        "commands": command_records,
        "events": symbol_records("events", events),
        "roles": symbol_records("roles", role_classes),
        "jobs": symbol_records("jobs", job_classes),
        "companion_jobs": symbol_records("companion_jobs", companion_job_classes),
        "persistence_participants": persistence_records,
        "persistence_stores": persistence_store_records_value,
        "operation_matrix": operation_records,
    }
    attach_fixture_references(surfaces, coverage, fixture_catalog)

    manifest = {
        "manifest_version": 4,
        "artifact": {
            "name": jar.name,
            "sha256": sha256,
            "sha1": sha1,
            "evidence_state": "VERIFIED_TARGET_SOURCE",
            "archive_integrity": artifact.get("archive_integrity"),
        },
        "counts": {
            "archive_entries": len(entries),
            "classes": len(target_classes),
            "assets": len(entries_by_category["asset"]),
            "data": len(entries_by_category["data"]),
            "gui": len(gui_records),
            "packets": len(packet_records),
            "commands": len(command_records),
            "events": len(events),
            "roles": len(role_classes),
            "jobs": len(job_classes),
            "companion_jobs": len(companion_job_classes),
            "persistence_stores": persistence_schema["counts"]["catalog_stores"],
            "persistence_participants": len(persistence_records),
            "operations": len(operation_records),
        },
        "issue_mapping": SURFACE_ISSUES,
        "fixture_coverage": coverage,
        "surfaces": surfaces,
    }
    known_issue_ids = issue_ids_from_documents(
        [
            document_root / "CUSTOMNPCS_PARITY_ISSUE_REGISTER.md",
            document_root / "CUSTOMNPCS_PARITY_TRACEABILITY.md",
        ]
    )
    validate_manifest(manifest, known_issue_ids, issue_surface_claims, known_fixture_ids)
    verify_source_provenance(manifest, set(entries), research_root, decompiled_root)
    return manifest


def validate_manifest(
    manifest: dict[str, Any],
    known_issue_ids: set[str] | None = None,
    issue_surface_claims: dict[str, Any] | None = None,
    known_fixture_ids: set[str] | None = None,
) -> None:
    if not isinstance(manifest, dict):
        raise ValueError("target surface manifest must be an object")
    artifact = manifest.get("artifact")
    if not isinstance(artifact, dict) or any(
        artifact.get(field) != expected
        for field, expected in {
            "name": TARGET_NAME,
            "sha256": TARGET_SHA256,
            "sha1": TARGET_SHA1,
            "evidence_state": "VERIFIED_TARGET_SOURCE",
            "archive_integrity": "PASS",
        }.items()
    ):
        raise ValueError("target artifact identity or integrity metadata drift")
    if manifest.get("manifest_version") != 4:
        raise ValueError("manifest_version must be 4")
    counts = manifest.get("counts", {})
    if not isinstance(counts, dict):
        raise ValueError("manifest counts must be an object")
    for key, expected in EXPECTED_COUNTS.items():
        if counts.get(key) != expected:
            raise ValueError(f"{key}: expected {expected}, got {counts.get(key)}")

    surfaces = manifest.get("surfaces")
    if not isinstance(surfaces, dict) or set(surfaces) != set(SURFACE_COUNT_KEYS):
        present = sorted(surfaces) if isinstance(surfaces, dict) else type(surfaces).__name__
        raise ValueError(f"surface set mismatch: {present}")
    if manifest.get("issue_mapping") != SURFACE_ISSUES:
        raise ValueError("issue mapping drift")
    if issue_surface_claims is not None:
        if not isinstance(issue_surface_claims, dict):
            raise ValueError("issue surface claims must be an object")
        expected_claims: dict[str, set[str]] = {}
        for surface_name, issue_ids in SURFACE_ISSUES.items():
            for issue_id in issue_ids:
                expected_claims.setdefault(issue_id, set()).add(surface_name)
        if set(issue_surface_claims) != set(expected_claims):
            raise ValueError("issue surface claims drift")
        for issue_id, surfaces_for_issue in issue_surface_claims.items():
            if (
                not isinstance(surfaces_for_issue, list)
                or not all(isinstance(surface, str) for surface in surfaces_for_issue)
                or len(surfaces_for_issue) != len(set(surfaces_for_issue))
                or set(surfaces_for_issue) != expected_claims[issue_id]
            ):
                raise ValueError(f"issue surface claims drift for {issue_id}")

    coverage = manifest.get("fixture_coverage")
    if not isinstance(coverage, dict) or set(coverage) != {"domains", "operation_families"}:
        raise ValueError("fixture_coverage must map domains and operation_families")
    for coverage_name, mapping in coverage.items():
        if not isinstance(mapping, dict):
            raise ValueError(f"fixture_coverage.{coverage_name} must be an object")
        for key, fixture_ids in mapping.items():
            if not isinstance(fixture_ids, list) or not fixture_ids:
                raise ValueError(f"fixture_coverage.{coverage_name}[{key}] must be non-empty")
            if len(fixture_ids) != len(set(fixture_ids)):
                raise ValueError(f"fixture_coverage.{coverage_name}[{key}] contains duplicate fixture IDs")
            if known_fixture_ids is not None and not set(fixture_ids).issubset(known_fixture_ids):
                raise ValueError(f"fixture_coverage.{coverage_name}[{key}] references an unknown fixture")
    crosswalk_fixture_ids = {
        fixture_id
        for mapping in coverage.values()
        for fixture_ids in mapping.values()
        for fixture_id in fixture_ids
    }

    for surface_name, expected_count_key in SURFACE_COUNT_KEYS.items():
        records = surfaces[surface_name]
        if not isinstance(records, list) or len(records) != counts[expected_count_key]:
            raise ValueError(
                f"{surface_name}: expected {counts[expected_count_key]} records, "
                f"got {len(records) if isinstance(records, list) else 'non-list'}"
            )
        symbols: set[str] = set()
        inventory_ids: set[str] = set()
        operation_ids: set[str] = set()
        for item in records:
            if not isinstance(item, dict):
                raise ValueError(f"{surface_name} contains a non-object record")
            required = {
                "symbol",
                "domain",
                "operation_family",
                "surface",
                "inventory_id",
                "issue_ids",
                "evidence_state",
                "provenance",
                "source",
                "parity_fixture_ids",
                "coverage_status",
            }
            missing = required - set(item)
            if surface_name == "operation_matrix":
                missing |= {"id", "closing_issue_ids"} - set(item)
            if missing:
                raise ValueError(f"{surface_name} record missing {sorted(missing)}: {item}")
            if surface_name == "operation_matrix":
                operation_id = item.get("id")
                if not isinstance(operation_id, str) or operation_id not in OPERATION_CLOSING_ISSUES:
                    raise ValueError(f"unknown operation ID: {operation_id!r}")
                if item["symbol"] != operation_id:
                    raise ValueError(f"operation symbol must equal operation ID: {operation_id}")
                if operation_id in operation_ids:
                    raise ValueError(f"duplicate operation ID: {operation_id}")
                operation_ids.add(operation_id)
            for field in (
                "symbol",
                "domain",
                "operation_family",
                "inventory_id",
                "evidence_state",
                "provenance",
                "source",
            ):
                if not isinstance(item[field], str) or not item[field].strip():
                    raise ValueError(f"{surface_name} {field} must be a non-empty string")
            if not isinstance(item["issue_ids"], list) or not item["issue_ids"] or not all(
                isinstance(issue_id, str) and issue_id for issue_id in item["issue_ids"]
            ):
                raise ValueError(f"{surface_name} issue_ids must be a non-empty string list")
            if item["surface"] != surface_name:
                raise ValueError(f"invalid mapping for {surface_name}: {item}")
            if item["provenance"] != SURFACE_PROVENANCE[surface_name]:
                raise ValueError(f"provenance drift for {surface_name}/{item['symbol']}")
            if item["evidence_state"] != (
                "INFERENCE_BACKED_BY_VERIFIED_TARGET_SOURCE"
                if surface_name == "operation_matrix"
                else "VERIFIED_TARGET_SOURCE"
            ):
                raise ValueError(f"evidence state drift for {surface_name}/{item['symbol']}")
            source = item["source"]
            source_parts = source.split("/")
            if (
                "\\" in source
                or source.startswith("/")
                or ":" in source_parts[0]
                or any(part in {"", ".", ".."} for part in source_parts)
            ):
                raise ValueError(f"source path is not a safe relative path for {surface_name}/{item['symbol']}")
            provenance = SURFACE_PROVENANCE[surface_name]
            if provenance == "jar-class" and source != item["symbol"].replace(".", "/") + ".class":
                raise ValueError(f"JAR class source disagrees with symbol for {surface_name}/{item['symbol']}")
            if provenance == "jar-entry" and source != item["symbol"]:
                raise ValueError(f"JAR entry source disagrees with symbol for {surface_name}/{item['symbol']}")
            if provenance == "decompiled-source" and not source.endswith(".java"):
                raise ValueError(f"decompiled source must be a Java path for {surface_name}/{item['symbol']}")
            if surface_name == "persistence_stores":
                source_files = item.get("source_files")
                source_hashes = item.get("source_sha256_by_file")
                if (
                    not isinstance(source_files, list)
                    or not source_files
                    or not all(isinstance(value, str) and value.endswith(".java") for value in source_files)
                    or len(source_files) != len(set(source_files))
                ):
                    raise ValueError(f"persistence source_files missing or invalid for {item['symbol']}")
                if source_files[0] != source:
                    raise ValueError(f"persistence primary source drift for {item['symbol']}")
                if not isinstance(source_hashes, dict) or set(source_hashes) != set(source_files):
                    raise ValueError(f"persistence source hash map missing or invalid for {item['symbol']}")
                if any(
                    not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
                    for value in source_hashes.values()
                ):
                    raise ValueError(f"invalid persistence source hash for {item['symbol']}")
                if source_hashes.get(source) != item.get("source_sha256"):
                    raise ValueError(f"persistence primary source hash drift for {item['symbol']}")
            if provenance == "research-inventory" and source != "inventories/parity_acceptance_matrix.json":
                raise ValueError(f"research source path drift for {surface_name}/{item['symbol']}")
            source_sha256 = item.get("source_sha256")
            if source_sha256 is not None and (
                not isinstance(source_sha256, str)
                or not re.fullmatch(r"[0-9a-f]{64}", source_sha256)
            ):
                raise ValueError(f"invalid source_sha256 for {surface_name}/{item['symbol']}")
            if provenance == "research-inventory" and source_sha256 is None:
                raise ValueError(f"research source hash missing for {surface_name}/{item['symbol']}")
            if not re.fullmatch(rf"target\.{re.escape(surface_name)}\.\d{{4}}", item["inventory_id"]):
                raise ValueError(f"invalid inventory ID for {surface_name}: {item['inventory_id']}")
            if item["symbol"] in symbols:
                raise ValueError(f"duplicate symbol for {surface_name}: {item['symbol']}")
            if item["inventory_id"] in inventory_ids:
                raise ValueError(f"duplicate inventory ID for {surface_name}: {item['inventory_id']}")
            symbols.add(item["symbol"])
            inventory_ids.add(item["inventory_id"])
            if surface_name == "roles":
                expected_role_issues = ROLE_ISSUES.get(item["symbol"])
                if expected_role_issues is None or item["issue_ids"] != expected_role_issues:
                    raise ValueError(f"role issue mapping drift for {item['symbol']}")
            refs = item["parity_fixture_ids"]
            if (
                not isinstance(refs, list)
                or not all(isinstance(ref, str) for ref in refs)
                or len(refs) != len(set(refs))
            ):
                raise ValueError(f"invalid parity_fixture_ids for {surface_name}/{item['symbol']}")
            if not set(refs).issubset(crosswalk_fixture_ids):
                raise ValueError(f"parity fixture is absent from crosswalk for {surface_name}/{item['symbol']}")
            if known_fixture_ids is not None and not set(refs).issubset(known_fixture_ids):
                raise ValueError(f"unresolved parity fixture reference for {surface_name}/{item['symbol']}")
            if surface_name == "operation_matrix":
                expected_refs = coverage["operation_families"].get(item.get("id"))
            elif surface_name != "roles":
                expected_refs = coverage["domains"].get(item["domain"])
            else:
                expected_refs = None
            if expected_refs is not None and refs != expected_refs:
                raise ValueError(f"fixture crosswalk disagrees with {surface_name}/{item['symbol']}")
            expected_coverage = "FIXTURE_REFERENCED" if refs else "INVENTORY_ONLY"
            if item["coverage_status"] != expected_coverage:
                raise ValueError(f"coverage status disagrees with fixture references for {surface_name}/{item['symbol']}")
            if surface_name == "operation_matrix":
                expected_closers = OPERATION_CLOSING_ISSUES.get(item.get("id"))
                if not expected_closers or item.get("closing_issue_ids") != expected_closers:
                    raise ValueError(f"operation closing issue map drift for {item.get('id')}")
                if known_issue_ids is not None and not set(expected_closers).issubset(known_issue_ids):
                    raise ValueError(f"unknown operation closing issue for {item.get('id')}")
            if known_issue_ids is not None and not set(item["issue_ids"]).issubset(known_issue_ids):
                raise ValueError(f"unknown issue mapping for {surface_name}: {item['issue_ids']}")
            if issue_surface_claims is not None:
                for issue_id in item["issue_ids"]:
                    claimed_surfaces = issue_surface_claims.get(issue_id, [])
                    if surface_name not in claimed_surfaces:
                        raise ValueError(
                            f"issue {issue_id} does not claim surface {surface_name}"
                        )
            for key, value in item.items():
                if isinstance(value, str) and len(value) > MAX_FIELD_LENGTH:
                    raise ValueError(f"field exceeds {MAX_FIELD_LENGTH} characters: {surface_name}/{key}")
            if len(json.dumps(item, sort_keys=True)) > MAX_RECORD_BYTES:
                raise ValueError(f"record exceeds {MAX_RECORD_BYTES} bytes: {surface_name}/{item['symbol']}")
        if surface_name == "roles" and symbols != set(ROLE_ISSUES):
            raise ValueError("role symbol set drift")
        if surface_name == "operation_matrix" and operation_ids != set(OPERATION_CLOSING_ISSUES):
            missing_ids = sorted(set(OPERATION_CLOSING_ISSUES) - operation_ids)
            extra_ids = sorted(operation_ids - set(OPERATION_CLOSING_ISSUES))
            raise ValueError(f"operation ID set drift: missing={missing_ids}, extra={extra_ids}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--research-root", type=Path, required=True)
    parser.add_argument("--decompiled-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--document-root",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "docs",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = build_manifest(
            args.jar.resolve(),
            args.research_root.resolve(),
            args.decompiled_root.resolve(),
            args.document_root.resolve(),
        )
        encoded = json.dumps(manifest, indent=2) + "\n"
        if len(encoded.encode("utf-8")) > MAX_OUTPUT_BYTES:
            raise ValueError(f"manifest exceeds {MAX_OUTPUT_BYTES} bytes")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"target surface manifest failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(manifest["counts"], sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
