# P1-4 — Actor capability and authorization policy closeout

Status: `IN-REVIEW`

## Delivered locally

- `MutationRequest` now carries an optional permission-level proof while retaining a compatibility constructor for trusted non-player adapters.
- `AuthorizationPolicy` fails closed for unknown actor types and capabilities, denies script escalation, and requires operator-level proof for `player:<uuid>` definition mutations.
- `StoryNpcsApplicationService.executeCanonicalMutation` evaluates authorization before replay lookup or operation execution, publishes an observable canonical rejection event, and returns `REJECTED_AUTHORIZATION` diagnostics without side effects.
- Network editor mutations pass the server-checked level-2 proof into the canonical request; existing permission-denied result packets remain correlated to request ID and revision.
- `CapabilityRegistry` classifies every routed capability as `DEFINITION` or `PLAYER_SCOPED`; `AuthorizationPolicy` consults it for both boundaries, so unregistered or misrouted capabilities fail closed with `UNKNOWN_CAPABILITY`.
- Economy/runtime operations now carry the same actor/subject/capability envelope as progression:
  - `BankOperationRequest` (actions: `DEPOSIT`, `DEPOSIT_AUTO`, `DEPOSIT_HELD`, `WITHDRAW`, `WITHDRAW_STACK`, `REMOVE_STACK`, `UNLOCK_TAB`) authorizes before vault mutation; banker-scoped requests emit a `CanonicalMutationEvent` per attempt.
  - `TradeExecutionRequest` authorizes before the journaled trade mutation and emits a canonical event for the trader target on every attempt.
  - Compat delegates (`depositToBank`, `depositToBankAuto`, `depositHeldToBank`, `withdrawFromBank`, `withdrawEntireStackFromBank`, `withdrawAndDeliverFromBank`, `unlockBankTab`, `executeTrade`) all route through the typed boundary as `player` self-subject envelopes; journaled request-id replay semantics are unchanged.
- Network role packets (`handleTradeAction`, `handleBankAction`) now construct typed requests bound to the session player and role NPC — the actor cannot choose a different subject.

### Adapter coverage audit (second slice)

Every remote-reachable adapter path was swept for mutation calls that bypass `AuthorizationPolicy`. Findings and fixes:

- `readMail`/`deleteMail` commands called the unguarded `markMailRead`/`deleteMail` UUID overloads. They now build `PlayerProgressionActionRequest` envelopes (`mail.read`/`mail.delete`, `command` actor bound to the command source, server-proven permission level) and surface the denial code to the player instead of mutating silently.
- `transport` command called the unguarded `requestTransport(UUID, NamespacedId)` — a world+economy mutation (emerald fee, teleport). A typed overload `requestTransport(PlayerProgressionActionRequest, NamespacedId)` now evaluates `transport.request` authorization (self for `player`/`dialogue`, self-or-op-2 for `command`, script denied) before any fee or movement; the command routes through it.
- `CapabilityRegistry` now registers all `PlayerProgressionActionRequest` operations (`mail.read`, `mail.delete`, `transport.unlock`, `transport.request`, `dialogue.visit.record`) as `PLAYER_SCOPED`, and `AuthorizationPolicy.evaluate(PPAR)` fails closed with `UNKNOWN_CAPABILITY` for unregistered operations — every operation now declares a required capability.
- Verified already-clean surfaces: network editor mutations (`player:<uuid>` + proven level), bank/trade packets (session-bound typed requests), `startQuest`/`completeQuest`/`setFollowerState`/`setFollowerFormation` commands (typed progression/follower requests), wand/path/dialogue items (server-side `hasPermissions(2)` proof then typed `MutationRequest`), `listMail <player>` (op-2 gated cross-read), `import` command (op-2 gated; `RegistryImportSink` runs inside the op boundary), `chargeCompanionWage` (entity-internal lifecycle, not adapter-reachable).
- Unguarded convenience overloads (`markMailRead(UUID,…)`, `deleteMail(UUID,…)`, `unlockTransportLocation(UUID,…)`, `deliverMail`, `createTransportLocation(TransportLocation)`, `requestTransport(UUID,…)`) were demoted to package-private/internal — they had zero non-test callers, so making them unreachable removes the bypass surface entirely rather than relying on convention.

### Review remediation (third slice)

Adversarial review of the adapter slice surfaced three hardening items, all implemented:

- **Operation↔method binding**: every typed PPAR overload asserts the envelope's `operation` matches the invoked method — a `mail.read` envelope passed to `deleteMail` is denied `OPERATION_MISMATCH`, so the audit label can never misrepresent the executed action.
- **Replay safety**: `PlayerProgressionActionRequest` results are journaled in a `BoundedReplayCache` keyed by request id + payload fingerprint. Replays return the recorded outcome (`duplicate=true` on `AuthorizedActionResult`, verbatim `TransportResult` for transport) without re-applying effects — a replayed `transport.request` cannot double-charge the emerald fee. Payload-mismatched replays are denied `REQUEST_PAYLOAD_MISMATCH`. Denied requests are never journaled, so a permission change takes effect on the next request.
- **Denial observability**: every PPAR attempt — allowed, denied, or replayed — publishes a `CanonicalMutationEvent` carrying operation, actor type/id, subject, request id, and outcome, matching the audit standard of the bank/trade boundary.

## Verification

- `AuthorizationPolicyTest`: registered capability allowlist, unknown actor/capability, player proof, and script denial.
- `StoryNpcsApplicationServiceTest`: unauthorized player mutation is rejected before the detached mutation operation runs and leaves the live definition unchanged.
- `CanonicalRuntimeMutationTest` (extended): cross-subject denial with observable event and unchanged vault, script denial, command self-vs-operator proof, self-subject deposit/withdraw/remove/unlock application, request-id replay idempotency for tab unlock, action route mismatch, held-deposit auth-before-server gating, typed trade auth-to-commit in headless mode, compat-delegate boundary routing, request input bounds, and registry classification incl. player-scoped capability misroute.
- `canonicalTransportRequestRequiresAuthorization`: cross-subject denial, script denial, unregistered-operation denial, and authorized self-request reaching the server boundary (`SERVER_UNAVAILABLE` in headless tests) — proving the envelope is evaluated before the world/economy mutation.
- `pparOperationsBindOperationReplaySafelyAndAuditEveryAttempt`: `OPERATION_MISMATCH` on cross-method envelopes, denied-request retry applying after permission upgrade, replay `duplicate` without re-application, `REQUEST_PAYLOAD_MISMATCH` on rebound payloads, and canonical audit events for every attempt class.
- `pparTransportReplayReturnsRecordedResultWithoutReapplying`: journaled transport result returned verbatim on replay; rebound-location replay rejected.
- Focused service tests: `BUILD SUCCESSFUL`.
- Full suite after this slice: `BUILD SUCCESSFUL`, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.

## Remaining risks

- Permission-node integration remains platform-adapter work; the policy consumes a proven level rather than resolving NeoForge permission nodes itself.
- The policy still permits `system` actor envelopes unconditionally; any future system-actor path that can be triggered remotely must be reviewed at the adapter level.
- `REMOVE_STACK` (vault-only removal) is exposed at the canonical boundary as an internal primitive leg; adapters should prefer `WITHDRAW_STACK` (remove-and-deliver) for player-facing flows.
