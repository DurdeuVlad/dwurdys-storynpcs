#!/usr/bin/env python3
"""P11-2 one-shot catalog update: unblock implemented fixtures and refresh coverage scopes."""
import json

path = 'docs/parity/fixture-catalog.json'
with open(path) as fh:
    cat = json.load(fh)

tm = cat['storynpcs_test_map']
cov = cat['storynpcs_test_coverage']

def sel(c, m):
    return f"com.storynpcs.{c}#{m}()"

add = {
 'P0-4.jobs': [sel('domain.P6DomainTest', 'allElevenJobsHaveTypedConfigsAndLifecycle')],
 'P0-4.transport': [sel('domain.P6DomainTest', 'transportEvaluationFailsBeforeFee')],
 'P0-4.spawner': [sel('creator.P8DomainTest', 'spawnerRuleIsDeterministicAndBounded'),
                  sel('creator.P8DomainTest', 'naturalSpawnRulesAreBounded')],
 'P0-4.creator-tools': [sel('creator.P8DomainTest', 'waypointEditingAndTraversalModes'),
                        sel('creator.P8DomainTest', 'worldToolDefinitionsCarryInertHooks'),
                        sel('creator.P8DomainTest', 'carpentryRecipeValidatesGridAndSlots')],
 'P0-4.scripting': [sel('P9DomainTest', 'hookMatrixCoversAllTargetHooks'),
                    sel('P9DomainTest', 'meterEnforcesAllQuotaAxes'),
                    sel('P9DomainTest', 'schedulerAggregatesBudgetAndQuarantinesRepeatOffenders')],
 'P0-4.companion': [sel('domain.P6DomainTest', 'wageChargesExactlyOncePerPeriod'),
                    sel('domain.P6DomainTest', 'companionProfileBoundsAndStageSelection')],
 'P0-4.marks': [sel('domain.npc.NpcInventoryTest', 'markCollectionsBoundAndCarryAvailability')],
 'P0-4.trade': [sel('domain.P7DomainTest', 'twoInputsAndOutputValidateBeforeCommit'),
                sel('domain.P7DomainTest', 'restockResetsUsesDeterministically'),
                sel('domain.P7DomainTest', 'purchaseEligibilityIsServerSideAndBounded')],
 'P0-4.bank': [sel('domain.P7DomainTest', 'tabCountCannotExceedSix'),
               sel('domain.P7DomainTest', 'accessPolicyIsPrivateByDefaultAndSharedOnlyWhenListed')],
 'P0-4.quest-completion': [sel('service.QuestProgressionMutationTest', 'experienceRewardIsDeliveredExactlyOnceAcrossFailedCommitAndRestart'),
                sel('service.QuestProgressionMutationTest', 'rewardDeliveryCrashAfterDurableMarkIsNeverRedelivered'),
                sel('service.QuestProgressionMutationTest', 'rewardMarkPersistenceFailureAbortsBeforeAnyDelivery'),
                sel('service.QuestProgressionMutationTest', 'loginRecoveryResumesInterruptedRewardFanOutExactlyOnce'),
                sel('domain.quest.QuestMailStoreTest', 'mailSurvivesReopenAndClaimsExactlyOnce'),
                sel('domain.quest.QuestMailStoreTest', 'overflowPolicyDefaultsToMail'),
                sel('domain.quest.QuestMailStoreTest', 'teamProgressionSurvivesMemberChangesAndRevisions')],
 'P0-4.dialogue-choice': [sel('domain.dialogue.DialogueChoiceProtocolTest', 'acceptedTokenCarriesBoundChoiceAndConsumesExactlyOnce'),
                sel('domain.dialogue.DialogueChoiceProtocolTest', 'indexOnlyAndUnknownTokensRejected'),
                sel('domain.dialogue.DialogueChoiceProtocolTest', 'expiryRejectsLateAccepts'),
                sel('domain.dialogue.DialogueChoiceProtocolTest', 'sessionRevocationDropsPendingTokens')],
 'P0-4.dialogue-definition': [sel('domain.dialogue.DialogueGraphValidatorTest', 'unreachableNodeAndDanglingEdgeDiagnosed'),
                sel('domain.dialogue.DialogueGraphValidatorTest', 'cyclesDiagnosedAsWarningsNotErrors')],
 'P0-4.faction': [sel('domain.P6DomainTest', 'factionMatrixFieldsAndBounds'),
                  sel('domain.P6DomainTest', 'deletionPlanRepairsOrFailsExplicitly')],
 'P0-4.roles': [sel('domain.P6DomainTest', 'socialRolesCarryBoundedConfig')],
 'P0-4.commands': [sel('P9DomainTest', 'suggestionsRejectInvalidIdsAndFields')],
 'P0-4.ai': [sel('domain.npc.NpcAiPolicyTest', 'policyDefaultsAreDeterministic'),
             sel('creator.P8DomainTest', 'waypointEditingAndTraversalModes')],
}
for k, v in add.items():
    existing = tm.get(k, [])
    tm[k] = existing + [s for s in v if s not in existing]

cat['storynpcs_test_blockers'] = {}

cov['P0-4.jobs'] = {
 'observed_scope': 'P6-4 domain tests cover all eleven job types with typed configs and bounded lifecycle (start/stop on unload, tick-cost reporting).',
 'uncovered_scope': 'No live entity job-tick, chunk-loader world interaction, or target-runtime job fixture.'}
cov['P0-4.transport'] = {
 'observed_scope': 'P6-3 domain tests cover transport evaluation: visibility/unlock/permission/destination checks fail before fees are charged.',
 'uncovered_scope': 'No live cross-dimension teleport, client UI session, or target-runtime transport fixture.'}
cov['P0-4.spawner'] = {
 'observed_scope': 'P8-1 domain tests cover deterministic bounded spawner rules and bounded natural-spawn rules.',
 'uncovered_scope': 'No live world spawn execution, chunk-load spawn, or target-runtime spawner fixture.'}
cov['P0-4.creator-tools'] = {
 'observed_scope': 'P8-2/P8-3 domain tests cover waypoint editing/traversal modes, inert world-tool hook bindings, and carpentry grid/slot validation.',
 'uncovered_scope': 'No live item-use session, world mutation execution, or target-runtime creator-tool fixture.'}
cov['P0-4.scripting'] = {
 'observed_scope': 'P9-1/P9-2 tests cover the nine-hook matrix, instruction/memory/depth/canonical-op quotas, and aggregate per-tick scheduler budget with quarantine.',
 'uncovered_scope': 'No JS/engine runtime inside hooks, live script-triggered world effects, or target-runtime script fixture.'}
cov['P0-4.companion'] = {
 'observed_scope': 'Follower tests cover owner-only state/formation changes and deterministic formation-slot allocation; P6-5 tests cover exactly-once wage charging and bounded companion stages/talents/inventory.',
 'uncovered_scope': 'No live companion entity lifecycle, dismissal/death world effects, owner logout world behavior, or target-runtime companion fixture.'}
cov['P0-4.marks'] = {
 'observed_scope': 'NpcInventoryTest covers bounded mark collections with availability flags; serde test covers mark type/color/text round-trip.',
 'uncovered_scope': 'No client mark rendering, resync, or target-runtime mark fixture.'}
cov['P0-4.trade'] = {
 'observed_scope': 'JVM tests cover stock/restock, replay, reservation rollback, max-use concurrency, durable listing-use reload; P7-1 tests cover two-input validation before commit, deterministic restock reset, and server-side purchase eligibility.',
 'uncovered_scope': 'No live player purchase GUI flow, output inventory delivery, or restart recovery fixture.'}
cov['P0-4.bank'] = {
 'observed_scope': 'Repository tests cover durable vault reload, tab unlock bounds, request replay, recovery serialization, invalid-tab rejection; P7-2 tests cover the six-tab bound and private/shared access policy.',
 'uncovered_scope': 'No live bank GUI/session fixture or process-kill inventory-delivery recovery fixture.'}
cov['P0-4.quest-completion'] = {
 'observed_scope': 'Application-service tests cover progression/reward completion, duplicate reward prevention, invalid-reward preflight, mark-before-deliver exactly-once XP delivery across failed commit and restart, crash-after-mark no-redelivery, mark-failure aborts before delivery, and login-time recovery resuming interrupted fan-out exactly once; P5-5 tests cover durable mail overflow, once-only claiming, and team progression surviving member changes.',
 'uncovered_scope': 'No process-kill/live-server recovery fixture beyond headless crash-injection and no target-runtime completion fixture.'}
cov['P0-4.dialogue-choice'] = {
 'observed_scope': 'Application-service tests cover conditional graph choice actions, invalid option indexes, and close-dialogue actions; P5-2 protocol tests cover opaque server-issued tokens bound to actor/dialogue/session/revision, exactly-once consumption, index/unknown/stale-token rejection, expiry, and session revocation.',
 'uncovered_scope': 'No live packet-to-service choice fixture, concurrent-choice determinism on a live server, or target-runtime choice fixture.'}
cov['P0-4.quest-progression']['observed_scope'] += ' P2-3 added mark-before-deliver durable reward legs with login-time recovery of interrupted fan-out.'
cov['P0-4.quest-progression']['uncovered_scope'] = 'Quest reward fan-out across a real process kill is exercised only by headless crash-injection fixtures; remaining operation families beyond quest/faction/follower/bank/trade route through typed requests but lack live-server fixtures; target runtime behavior remains unverified.'
cov['P0-4.dialogue-definition']['observed_scope'] += ' P5-1 validator tests cover unreachable-node/dangling-edge errors and legal-cycle warnings.'
cov['P0-4.faction'] = {
 'observed_scope': 'JVM tests cover faction YAML load, arithmetic clamp behavior, service-owned mutation; P6-1 tests cover the relationship matrix, bounded points, and deletion-plan repair/failure.',
 'uncovered_scope': 'No live faction UI/network adapter or full provenance/threshold lifecycle fixture.'}
cov['P0-4.roles']['observed_scope'] += ' P6-2 tests cover bounded postman/healer/bard role configs and lifecycle/permission surfaces.'
cov['P0-4.commands']['observed_scope'] += ' P9-3 tests cover deterministic suggestion filtering and invalid id/field rejection.'
cov['P0-4.ai']['observed_scope'] += ' P3-3 policy tests cover deterministic targeting defaults; P8-2 tests cover waypoint add/move/delete/loop/ping-pong/once.'

with open(path, 'w') as fh:
    json.dump(cat, fh, indent=1)
    fh.write('\n')
print('catalog updated; blockers:', cat['storynpcs_test_blockers'])
