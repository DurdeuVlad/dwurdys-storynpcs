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

## Verification

- `AuthorizationPolicyTest`: registered capability allowlist, unknown actor/capability, player proof, and script denial.
- `StoryNpcsApplicationServiceTest`: unauthorized player mutation is rejected before the detached mutation operation runs and leaves the live definition unchanged.
- `CanonicalRuntimeMutationTest` (extended): cross-subject denial with observable event and unchanged vault, script denial, command self-vs-operator proof, self-subject deposit/withdraw/remove/unlock application, request-id replay idempotency for tab unlock, action route mismatch, held-deposit auth-before-server gating, typed trade auth-to-commit in headless mode, compat-delegate boundary routing, request input bounds, and registry classification incl. player-scoped capability misroute.
- Focused service tests: `BUILD SUCCESSFUL`.
- Full suite after this slice: `BUILD SUCCESSFUL`, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.

## Remaining risks

- Permission-node integration remains platform-adapter work; the policy consumes a proven level rather than resolving NeoForge permission nodes itself.
- The policy still permits `system` actor envelopes unconditionally; any future system-actor path that can be triggered remotely must be reviewed at the adapter level.
- `REMOVE_STACK` (vault-only removal) is exposed at the canonical boundary as an internal primitive leg; adapters should prefer `WITHDRAW_STACK` (remove-and-deliver) for player-facing flows.
