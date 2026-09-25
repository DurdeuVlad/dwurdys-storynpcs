# P0-3 closeout — target-runtime evidence gate

Status: `DONE-LOCAL` — evidence states and certification blocking are executable; target runtime parity remains unproven until real probes run.

## Delivered

- Added `tools/parity/evidence_gate.py`.
- Added `tools/parity/test_evidence_gate.py` with 18 focused tests.
- Added `docs/parity/evidence-schema.json`.
- Defined explicit target and StoryNPCs probe statuses, comparison outcomes, evidence states, certification blockers, and `parity_status`.

## Verification

- `python -m unittest tools.parity.test_evidence_gate` — 18 passed.
- Independent adversarial re-review — `PASS`; no actionable P0/P1/P2 findings.
- `VERIFIED_PARITY` requires observed target and StoryNPCs probes, meaningful results, `MATCH`, and strict canonical JSON result equality.
- Missing, unavailable, blocked, unknown, malformed, empty, contradictory, duplicate, non-standard-number, and incomplete reports fail closed or remain explicitly `BLOCKED`.
- `INTENTIONAL_DEVIATION` requires rationale and migration impact.

`status=PASS` means the evidence report is structurally valid; `parity_status=VERIFIED` is emitted only when every fixture is certification-eligible. A valid unprobed report remains `parity_status=BLOCKED`.
