# P1-4 — Actor capability and authorization policy closeout

Status: `IN-REVIEW`

## Delivered locally

- `MutationRequest` now carries an optional permission-level proof while retaining a compatibility constructor for trusted non-player adapters.
- `AuthorizationPolicy` fails closed for unknown actor types and capabilities, denies script escalation, and requires operator-level proof for `player:<uuid>` definition mutations.
- `StoryNpcsApplicationService.executeCanonicalMutation` evaluates authorization before replay lookup or operation execution, publishes an observable canonical rejection event, and returns `REJECTED_AUTHORIZATION` diagnostics without side effects.
- Network editor mutations pass the server-checked level-2 proof into the canonical request; existing permission-denied result packets remain correlated to request ID and revision.

## Verification

- `AuthorizationPolicyTest`: registered capability allowlist, unknown actor/capability, player proof, and script denial.
- `StoryNpcsApplicationServiceTest`: unauthorized player mutation is rejected before the detached mutation operation runs and leaves the live definition unchanged.
- Focused service tests: `BUILD SUCCESSFUL`.
- Full suite after this slice: `BUILD SUCCESSFUL`, 268 tests, 0 failures/errors.
- Truth gate: passed. `git diff --check`: passed; only repository line-ending warnings.

## Remaining risks

- Command adapters still have historical direct service calls outside this policy's typed request boundary; P1-4 does not yet claim complete command/script/economy/world-tool coverage.
- Permission-node integration remains platform-adapter work; the policy consumes a proven level rather than resolving NeoForge permission nodes itself.
- Role actions currently use session binding and server validation but do not yet expose every future capability in the policy registry.
