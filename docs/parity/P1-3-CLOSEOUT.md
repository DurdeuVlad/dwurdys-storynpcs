# P1-3 — Network protocol hardening closeout

Status: `DONE-LOCAL`

## Delivered locally

- `MutationProtocolCodecs` prefixes every registered payload with schema version `1`, rejects unknown versions, bounds total encoded bytes, and restores the writer index after failed encodes.
- IDs, text, messages, JSON documents, registry snapshots, and dialogue lists use explicit UTF-8/count bounds.
- Editor mutation result payloads carry `code`, `requestId`, and `revision`; server adapters populate those fields for permission, validation, service, mutation, and applied outcomes.
- Dialogue opens carry a server-generated session ID. Dialogue choices carry that token and a request ID; stale tokens and duplicate requests are rejected before the canonical service call.
- Trade and bank opens carry server-issued per-player/NPC role session IDs. Actions carry the token and request ID; replacement, logout cleanup, and duplicate replay invalidate or reject them.
- NeoForge's connection-level registrar version remains `1.0.0`; the payload schema version is intentionally separate so a compatible connection cannot silently accept a changed DTO shape.

## Evidence

- `NetworkPayloadsTest`: round-trip, unknown-version, oversized-document, option-list-boundary, and partial-frame rollback cases.
- `RuntimeSessionRegistryTest`: stable role token, token replacement, replay rejection, and cleanup expiry.
- Focused test run after implementation: `BUILD SUCCESSFUL`.
- Full suite: `BUILD SUCCESSFUL`, 263 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.
- Static audit: all 21 current `*Payload.java` files use a `MutationProtocolCodecs` versioned envelope; no unbounded `STRING_UTF8` or unbounded string list remains in the network package.
- An independent read-only auditor was dispatched twice but did not return a handoff within bounded waits and was shut down. This is recorded as unavailable evidence, not approval; the main-agent adversarial review and focused tests are the local gate for this revision.

## Explicit limits

- Ordinary payloads: 64 KiB total encoded frame.
- Editor documents: 1 MiB total frame and JSON string bound.
- Registry snapshots: 4 MiB total frame and JSON string bound.
- Role snapshots: 2 MiB total frame, with each JSON field bounded to 1 MiB.
- Dialogue option/hint lists: 256 items; text fields: 16 KiB; IDs: 256 characters; result messages: 4 KiB.

## Remaining risks

- The target JAR's 115 server-bound and 40 client-bound surface mapping is not yet semantically proven against runtime fixtures.
- Generic editor-open packets still use the existing editor lifecycle rather than an explicit editor session token; they are protected by operator checks, request IDs, and expected revisions.
- Client screens consume structured result payloads, but a complete stale-editor auto-refresh workflow requires domain-specific reload payloads for dialogue/NPC editors.
- Live dedicated-server reconnect and malformed-network integration probes remain required before target-parity certification.

## Research basis

NeoForge documents `PayloadRegistrar` connection versioning, `StreamCodec` payload contracts, and bounded `ByteBufCodecs.stringUtf8`/`list(max)` codecs. The implementation follows those platform patterns and adds application-level request/session identity that the registrar does not provide.
