# P0-2 closeout — documentation truth gate

Status: `DONE-LOCAL` — unsupported completion claims are truth-gated; final parity certification remains open.

## Delivered

- Reworded README, Business, Decision, historical milestone, and UX documentation to distinguish target intent from delivered, partial, and unverified behavior.
- Added `tools/parity/check_truth_gate.py`.
- Added `tools/parity/test_truth_gate.py`.
- Added exact line-hash exceptions in `truth-gate-exceptions.json` for benchmark workloads and documents that must name the unsupported claims.
- Added the truth-gate step to `.github/workflows/ci.yml` before Gradle tests.

## Verification

- `python -m unittest tools.parity.test_truth_gate` — 5 passed.
- `python tools/parity/check_truth_gate.py` — `truth gate passed`.
- Three synthetic completion-claim bypass cases — all rejected by rule tests.
- Exception scope — all 25 configured hashes match current intended lines; unused exceptions fail.
- Independent adversarial re-review — `PASS`; no actionable P0/P1/P2 findings.
- `.\gradlew.bat test --console=plain --rerun-tasks` — `BUILD SUCCESSFUL`.

The exception file does not authorize arbitrary future claims: each entry is tied to one document, one rule, and one exact line hash. Historical UX claims remain explicitly marked unverified.
