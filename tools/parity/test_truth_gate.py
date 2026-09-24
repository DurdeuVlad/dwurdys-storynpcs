import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.parity.check_truth_gate import check_truth_gate


class TruthGateTest(unittest.TestCase):
    def test_repository_docs_pass(self) -> None:
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(check_truth_gate(root), [])

    def test_unallowlisted_completion_claim_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs" / "parity").mkdir(parents=True)
            (root / "README.md").write_text("100% mutation parity\n", encoding="utf-8")
            (root / "docs" / "parity" / "truth-gate-exceptions.json").write_text(
                json.dumps({"exceptions": []}), encoding="utf-8"
            )
            findings = check_truth_gate(root)
            self.assertEqual(findings[0]["rule"], "mutation_parity")

    def test_broad_completion_claim_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs" / "parity").mkdir(parents=True)
            (root / "README.md").write_text(
                "Complete CustomNPCs feature parity is delivered.\n", encoding="utf-8"
            )
            (root / "docs" / "parity" / "truth-gate-exceptions.json").write_text(
                json.dumps({"exceptions": []}), encoding="utf-8"
            )
            findings = check_truth_gate(root)
            self.assertEqual(findings[0]["rule"], "complete_feature_parity")

    def test_exception_is_scoped_to_document_and_rule(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs" / "parity").mkdir(parents=True)
            (root / "README.md").write_text("100% mutation parity\n", encoding="utf-8")
            (root / "Business.md").write_text("100% mutation parity\n", encoding="utf-8")
            (root / "docs" / "parity" / "truth-gate-exceptions.json").write_text(
                json.dumps(
                    {
                        "exceptions": [
                            {
                                "path": "README.md",
                                "rule": "mutation_parity",
                                "line_sha256": hashlib.sha256(
                                    "100% mutation parity\n".rstrip("\n").encode("utf-8")
                                ).hexdigest(),
                                "reason": "test",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            findings = check_truth_gate(root)
            self.assertEqual(findings[0]["path"], "Business.md")

    def test_unused_exception_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs" / "parity").mkdir(parents=True)
            (root / "README.md").write_text("truthful documentation\n", encoding="utf-8")
            (root / "docs" / "parity" / "truth-gate-exceptions.json").write_text(
                json.dumps(
                    {
                        "exceptions": [
                            {
                                "path": "README.md",
                                "rule": "mutation_parity",
                                "line_sha256": "0" * 64,
                                "reason": "test",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            findings = check_truth_gate(root)
            self.assertEqual(findings[0]["text"], "unused exception")


if __name__ == "__main__":
    unittest.main()
