import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

from tools.parity import check_feature_status as cfs


def write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def java(root: Path, rel: str, body: str = "") -> None:
    write(root, f"src/main/java/com/storynpcs/{rel}", body)


def make_map(root: Path, rows: list[dict], doc: str = "docs/DOC.md") -> None:
    write(root, "tools/parity/feature_status_map.json",
          json.dumps({"map_version": 1, "doc": doc, "features": rows}))


def doc_with_markers(root: Path, inner: str = "STALE") -> None:
    write(root, "docs/DOC.md",
          f"# Doc\n\n{cfs.BEGIN_MARKER}\n{inner}\n{cfs.END_MARKER}\n")


def run_main(root: Path, *extra: str) -> int:
    err = io.StringIO()
    with mock.patch("sys.argv", ["check_feature_status.py", "--root", str(root), *extra]):
        with redirect_stderr(err):
            code = cfs.main()
    return code


ROW = lambda **kw: {  # noqa: E731 — terse row builder
    "id": "feat",
    "feature": "Feat",
    "status": "partial",
    "issue": "#0",
    "summary": "s",
    "evidence": {"files": [], "wired": [], "registered": [], "tests": [], "absent": []},
    **kw,
}


class FeatureStatusTest(unittest.TestCase):
    def test_implemented_claim_requires_wiring_and_tests(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "domain/Thing.java", "class Thing {}")
            java(root, "app/Use.java", "class Use { Thing t; }")
            write(root, "src/test/java/x/ThingTest.java", "")
            make_map(root, [ROW(
                status="implemented",
                evidence={
                    "files": ["src/main/java/com/storynpcs/domain/Thing.java"],
                    "wired": [{"file": "src/main/java/com/storynpcs/app/Use.java", "pattern": "Thing"}],
                    "registered": [],
                    "tests": ["src/test/java/**/ThingTest.java"],
                    "absent": [],
                })])
            self.assertEqual(run_main(root), 0)

    def test_missing_claim_fails_when_code_exists(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "domain/Thing.java")
            make_map(root, [ROW(
                status="missing",
                evidence={"files": ["src/main/java/com/storynpcs/domain/Thing.java"],
                          "wired": [], "registered": [], "tests": [], "absent": []})])
            self.assertEqual(run_main(root), 1)

    def test_wired_without_tests_cannot_claim_implemented(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "domain/Thing.java")
            java(root, "app/Use.java", "Thing t;")
            make_map(root, [ROW(
                status="implemented",
                evidence={
                    "files": ["src/main/java/com/storynpcs/domain/Thing.java"],
                    "wired": [{"file": "src/main/java/com/storynpcs/app/Use.java", "pattern": "Thing"}],
                    "registered": [],
                    "tests": [],  # wired floor, but nothing tested
                    "absent": [],
                })])
            self.assertEqual(run_main(root), 1)

    def test_declared_isolation_rejects_cross_package_reference(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "pkg_a/Only.java", "class Only {}")
            java(root, "pkg_b/Consumer.java", "class Consumer { Only o; }")
            make_map(root, [ROW(
                status="declared",
                evidence={"files": ["src/main/java/com/storynpcs/pkg_a/Only.java"],
                          "wired": [], "registered": [], "tests": [], "absent": []})])
            self.assertEqual(run_main(root), 1)

    def test_declared_isolation_allows_same_package_references(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "pkg_a/Only.java", "class Only {}")
            java(root, "pkg_a/OnlySerde.java", "class OnlySerde { Only o; }")
            make_map(root, [ROW(
                status="declared",
                evidence={"files": ["src/main/java/com/storynpcs/pkg_a/Only.java"],
                          "wired": [], "registered": [], "tests": [], "absent": []})])
            self.assertEqual(run_main(root), 0)

    def test_absent_pattern_violation(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "x/Any.java", "import net.x.Registries; class Any { Registries.BLOCK b; }")
            make_map(root, [ROW(
                status="planned",
                evidence={"files": [], "wired": [], "registered": [], "tests": [],
                          "absent": ["Registries\\.BLOCK"]})])
            self.assertEqual(run_main(root), 1)

    def test_absent_pattern_ignores_comments(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "x/Any.java", "// TODO Registries.BLOCK someday\n/* also Registries.BLOCK */")
            make_map(root, [ROW(
                status="planned",
                evidence={"files": [], "wired": [], "registered": [], "tests": [],
                          "absent": ["Registries\\.BLOCK"]})])
            self.assertEqual(run_main(root), 0)

    def test_absent_exempt_skips_only_listed_files(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            # Parity catalogs legitimately name target classes as mapping data;
            # exempt files are skipped, but the pattern still scans everywhere else.
            java(root, "parity/Catalog.java", 'class Catalog { String s = "GuiQuestLog"; }')
            make_map(root, [ROW(
                status="planned",
                evidence={"files": [], "wired": [], "registered": [], "tests": [],
                          "absent": ["GuiQuestLog"],
                          "absent_exempt": [
                              "src/main/java/com/storynpcs/parity/Catalog.java"]})])
            self.assertEqual(run_main(root), 0)

    def test_absent_exempt_does_not_cover_other_files(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "parity/Catalog.java", 'class Catalog { String s = "GuiQuestLog"; }')
            java(root, "real/GuiQuestLog.java", "class GuiQuestLog {}")
            make_map(root, [ROW(
                status="planned",
                evidence={"files": [], "wired": [], "registered": [], "tests": [],
                          "absent": ["GuiQuestLog"],
                          "absent_exempt": [
                              "src/main/java/com/storynpcs/parity/Catalog.java"]})])
            self.assertEqual(run_main(root), 1)

    def test_registered_alone_cannot_reach_tested_floor(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "domain/Thing.java", "class Thing {}")
            java(root, "reg/Reg.java", "register(\"thing\")")
            write(root, "src/test/java/x/ThingTest.java", "")
            make_map(root, [ROW(
                status="implemented",
                evidence={
                    "files": ["src/main/java/com/storynpcs/domain/Thing.java"],
                    "wired": [],
                    "registered": [{"file": "src/main/java/com/storynpcs/reg/Reg.java",
                                    "pattern": "\"thing\""}],
                    "tests": ["src/test/java/**/ThingTest.java"],
                    "absent": [],
                })])
            self.assertEqual(run_main(root), 1)  # wired floor only

    def test_wired_signal_cannot_be_own_evidence_file(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "domain/Thing.java", "class Thing {}")
            make_map(root, [ROW(
                status="partial",
                evidence={
                    "files": ["src/main/java/com/storynpcs/domain/Thing.java"],
                    "wired": [{"file": "src/main/java/com/storynpcs/domain/Thing.java",
                               "pattern": "Thing"}],
                    "registered": [], "tests": [], "absent": [],
                })])
            self.assertEqual(run_main(root), 1)

    def test_declared_two_hop_isolation(self) -> None:
        # declared class -> same-package wrapper -> external consumer = wired, not declared
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            java(root, "pkg_a/Only.java", "class Only {}")
            java(root, "pkg_a/OnlyBox.java", "class OnlyBox { Only o; }")
            java(root, "pkg_b/Consumer.java", "class Consumer { OnlyBox b; }")
            make_map(root, [ROW(
                status="declared",
                evidence={"files": ["src/main/java/com/storynpcs/pkg_a/Only.java"],
                          "wired": [], "registered": [], "tests": [], "absent": []})])
            self.assertEqual(run_main(root), 1)

    def test_unknown_status_is_a_map_error(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_map(root, [ROW(status="bogus")])
            self.assertEqual(run_main(root), 1)

    def test_pipe_chars_escaped_in_generated_table(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_map(root, [ROW(status="missing", summary="cmd a|b|c")])
            doc_with_markers(root)
            self.assertEqual(run_main(root, "--write"), 0)
            doc = (root / "docs/DOC.md").read_text(encoding="utf-8")
            row = [ln for ln in doc.splitlines() if "cmd" in ln][0]
            import re as _re
            cells = [c for c in _re.split(r"(?<!\\)\|", row) if c.strip()]
            self.assertEqual(len(cells), 4)
            self.assertIn("cmd a\\|b\\|c", row)

    def test_check_fails_on_missing_markers(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_map(root, [ROW(status="missing")])
            write(root, "docs/DOC.md", "# no markers\n")
            self.assertEqual(run_main(root, "--check"), 1)

    def test_unresolved_evidence_is_a_finding(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_map(root, [ROW(
                status="partial",
                evidence={"files": ["src/main/java/com/storynpcs/nope/Gone.java"],
                          "wired": [], "registered": [], "tests": [], "absent": []})])
            self.assertEqual(run_main(root), 1)

    def test_check_fails_on_stale_doc_section(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_map(root, [ROW(status="missing")])
            doc_with_markers(root)
            self.assertEqual(run_main(root, "--check"), 1)
            self.assertEqual(run_main(root, "--write"), 0)
            self.assertEqual(run_main(root, "--check"), 0)

    def test_write_then_check_is_deterministic_and_crlf_safe(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_map(root, [ROW(status="missing"), ROW(id="f2", feature="F2", status="planned")])
            doc_with_markers(root)
            self.assertEqual(run_main(root, "--write"), 0)
            first = (root / "docs/DOC.md").read_bytes()
            # re-write → identical bytes
            self.assertEqual(run_main(root, "--write"), 0)
            self.assertEqual((root / "docs/DOC.md").read_bytes(), first)
            # CRLF-normalized doc still passes --check
            raw = first.replace(b"\n", b"\r\n")
            (root / "docs/DOC.md").write_bytes(raw)
            self.assertEqual(run_main(root, "--check"), 0)

    def test_real_repo_map_passes(self) -> None:
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(run_main(root, "--check"), 0)


if __name__ == "__main__":
    unittest.main()
