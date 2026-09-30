package com.storynpcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storynpcs.admin.ConfigTransaction;
import com.storynpcs.admin.PlayerDataScope;
import com.storynpcs.admin.RemoteAccessProof;
import com.storynpcs.api.ApiVersion;
import com.storynpcs.command.CommandSuggestionEngine;
import com.storynpcs.script.ScriptBudget;
import com.storynpcs.script.ScriptHook;
import com.storynpcs.script.ScriptScheduler;

class P9DomainTest {

    // --- P9-1 API ------------------------------------------------------------

    @Test
    void apiVersionNegotiationFailsClearly() {
        assertThat(ApiVersion.negotiate(new ApiVersion(1, 0, 0)))
                .isInstanceOf(ApiVersion.Negotiation.Compatible.class);
        assertThat(ApiVersion.negotiate(new ApiVersion(2, 0, 0)))
                .isInstanceOf(ApiVersion.Negotiation.Incompatible.class);
        assertThat(ApiVersion.negotiate(new ApiVersion(1, 99, 0)))
                .isInstanceOf(ApiVersion.Negotiation.Incompatible.class);
        assertThat(ApiVersion.negotiate(null))
                .isInstanceOf(ApiVersion.Negotiation.Incompatible.class);
    }

    // --- P9-2 scripting host -------------------------------------------------

    @Test
    void hookMatrixCoversAllTargetHooks() {
        assertThat(ScriptHook.values()).hasSize(9);
        assertThat(ScriptHook.TICK.budgetClass()).isEqualTo(ScriptHook.BudgetClass.TICK);
        assertThat(ScriptBudget.forHook(ScriptHook.TICK).maxInstructions()).isEqualTo(20_000);
        assertThat(ScriptBudget.forHook(ScriptHook.INIT).maxInstructions()).isEqualTo(100_000);
        assertThat(ScriptBudget.forHook(ScriptHook.DIALOG).maxCanonicalOps()).isEqualTo(32);
    }

    @Test
    void meterEnforcesAllQuotaAxes() {
        var meter = new ScriptBudget.Meter(new ScriptBudget(3, 16, 2, 1));
        meter.instruction(); meter.instruction(); meter.instruction();
        org.assertj.core.api.Assertions.assertThatThrownBy(meter::instruction)
                .isInstanceOf(ScriptBudget.BudgetExceeded.class);
        meter.allocate(16);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> meter.allocate(1))
                .isInstanceOf(ScriptBudget.BudgetExceeded.class);
        meter.enter(); meter.enter();
        org.assertj.core.api.Assertions.assertThatThrownBy(meter::enter)
                .isInstanceOf(ScriptBudget.BudgetExceeded.class);
        meter.canonicalOp();
        org.assertj.core.api.Assertions.assertThatThrownBy(meter::canonicalOp)
                .isInstanceOf(ScriptBudget.BudgetExceeded.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> meter.allocate(-1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> meter.release(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedulerAggregatesBudgetAndQuarantinesRepeatOffenders() {
        ScriptScheduler scheduler = new ScriptScheduler();
        UUID script = UUID.randomUUID();
        scheduler.register(script, UUID.randomUUID());
        scheduler.beginTick();
        assertThat(scheduler.dispatch(script, ScriptHook.TICK, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        assertThat(scheduler.dispatch(script, ScriptHook.TICK, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        assertThat(scheduler.dispatch(script, ScriptHook.TICK, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        assertThat(scheduler.dispatch(script, ScriptHook.TICK, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Completed.class);
        // 4ms aggregate exhausted at 4 x 1ms TICK hooks.
        assertThat(scheduler.dispatch(script, ScriptHook.TICK, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);

        // Over-budget runner → consecutive failures → quarantined.
        Runnable overflow = () -> {
            var m = new ScriptBudget.Meter(new ScriptBudget(1, 1 << 20, 16, 32));
            m.instruction(); m.instruction();
        };
        scheduler.beginTick();
        assertThat(scheduler.dispatch(script, ScriptHook.INTERACT, overflow))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.OverBudget.class);
        scheduler.beginTick();
        scheduler.dispatch(script, ScriptHook.INTERACT, overflow);
        scheduler.beginTick();
        scheduler.dispatch(script, ScriptHook.INTERACT, overflow);
        assertThat(scheduler.handleOf(script).status())
                .isEqualTo(ScriptScheduler.Status.QUARANTINED);
        assertThat(scheduler.dispatch(script, ScriptHook.INTERACT, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
        assertThat(scheduler.unquarantine(script)).isTrue();
    }

    @Test
    void schedulerBoundsRegisteredScriptsAndSupportsRemoval() {
        ScriptScheduler scheduler = new ScriptScheduler();
        UUID actor = UUID.randomUUID();
        UUID firstScript = null;
        for (int i = 0; i < ScriptScheduler.MAX_REGISTERED_SCRIPTS; i++) {
            UUID script = UUID.nameUUIDFromBytes(("script-" + i).getBytes());
            if (i == 0) firstScript = script;
            assertThat(scheduler.register(script, actor)).isTrue();
        }

        assertThat(scheduler.register(UUID.randomUUID(), actor)).isFalse();
        scheduler.unregister(firstScript);
        assertThat(scheduler.handleOf(firstScript)).isNull();
        assertThat(scheduler.register(UUID.randomUUID(), actor)).isTrue();
    }

    // --- P9-3 command suggestions --------------------------------------------

    @Test
    void suggestionsRejectInvalidIdsAndFields() {
        List<String> ids = List.of("storynpcs:guard", "storynpcs:knight", "other:misc");
        var suggestions = CommandSuggestionEngine.suggest(
                CommandSuggestionEngine.ArgKind.NPC_ID, "storynpcs:", () -> ids);
        assertThat(suggestions).containsExactly("storynpcs:guard", "storynpcs:knight");

        assertThat(CommandSuggestionEngine.argumentValid(
                CommandSuggestionEngine.ArgKind.NPC_ID, "storynpcs:guard")).isTrue();
        assertThat(CommandSuggestionEngine.argumentValid(
                CommandSuggestionEngine.ArgKind.NPC_ID, "no-namespace")).isFalse();
        assertThat(CommandSuggestionEngine.argumentValid(
                CommandSuggestionEngine.ArgKind.NPC_ID, "INVALID:CAPS")).isFalse();
        assertThat(CommandSuggestionEngine.argumentValid(
                CommandSuggestionEngine.ArgKind.FIELD, "displayName")).isTrue();
        assertThat(CommandSuggestionEngine.argumentValid(
                CommandSuggestionEngine.ArgKind.FIELD, "1bad")).isFalse();
        assertThat(CommandSuggestionEngine.parseId("bad!!")).isEmpty();
        assertThat(CommandSuggestionEngine.parseId("storynpcs:ok")).isPresent();
    }

    // --- P9-4 remote/admin/config ---------------------------------------------

    @Test
    void remoteProofRequiresSessionAndCapability() {
        RemoteAccessProof proof = new RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("admin.remote.edit"), 0, 100);
        assertThat(proof.permits("admin.remote.edit", 50)).isTrue();
        assertThat(proof.permits("admin.remote.edit", 101)).isFalse(); // expired
        assertThat(proof.permits("admin.world.edit", 50)).isFalse();   // missing capability
    }

    @Test
    void remoteProofEnforcesBothValidityBoundsAndCtorSanity() {
        RemoteAccessProof proof = new RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("admin.remote.edit"), 10, 100);
        // Fail closed on BOTH bounds: before issuance and after expiry.
        assertThat(proof.validAt(9)).isFalse();   // not yet issued
        assertThat(proof.validAt(10)).isTrue();   // issued boundary
        assertThat(proof.validAt(100)).isTrue();  // expiry boundary
        assertThat(proof.validAt(101)).isFalse(); // expired
        assertThat(proof.permits("admin.remote.edit", 5)).isFalse();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), Set.of(), -1, 50))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RemoteAccessProof(
                UUID.randomUUID(), UUID.randomUUID(), Set.of(), 50, 49))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RemoteAccessProof(
                null, UUID.randomUUID(), Set.of(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RemoteAccessProof(
                UUID.randomUUID(), null, Set.of(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedulerEnforcesWallClockElapsedWithoutMeterCooperation() {
        // Shrink the TICK-hook budget to the tunable floor (0.1 ms) so a
        // deliberately slow callback overruns deterministically — no meter use.
        var tunables = new com.storynpcs.admin.RuntimeTunables();
        var applied = tunables.applyChanges(Map.of(
                com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_HOOK_NANOS, "100000"), 0L);
        assertThat(applied.committed()).isTrue();
        ScriptScheduler scheduler = new ScriptScheduler(tunables);
        UUID script = UUID.randomUUID();
        scheduler.register(script, UUID.randomUUID());

        Runnable slow = () -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        for (int i = 0; i < 3; i++) {
            scheduler.beginTick();
            var outcome = scheduler.dispatch(script, ScriptHook.TICK, slow);
            // The callback never touched a meter — the scheduler's own
            // wall-clock measurement must still classify the overrun.
            assertThat(outcome).isInstanceOf(ScriptScheduler.DispatchOutcome.OverBudget.class);
        }
        // Repeated over-budget runs quarantine the script — fail closed.
        assertThat(scheduler.handleOf(script).status())
                .isEqualTo(ScriptScheduler.Status.QUARANTINED);
        assertThat(scheduler.dispatch(script, ScriptHook.TICK, () -> {}))
                .isInstanceOf(ScriptScheduler.DispatchOutcome.Skipped.class);
    }

    @Test
    void runtimeTunablesStageCommitAndRollbackAtomically() {
        var tunables = new com.storynpcs.admin.RuntimeTunables();
        assertThat(tunables.revision()).isZero();
        assertThat(tunables.longValue(com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS))
                .isEqualTo(600L);

        // A committed multi-key change applies atomically and bumps revision.
        var committed = tunables.applyChanges(Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS, "1200",
                com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS, "2000000"), 0L);
        assertThat(committed.committed()).isTrue();
        assertThat(committed.newRevision()).isEqualTo(1L);
        assertThat(tunables.longValue(com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS))
                .isEqualTo(1200L);

        // A stale expected revision rolls the whole change back — no partial apply.
        var stale = tunables.applyChanges(Map.of(
                com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS, "900000"), 0L);
        assertThat(stale.committed()).isFalse();
        assertThat(tunables.longValue(com.storynpcs.admin.RuntimeTunables.SCRIPT_TICK_AGGREGATE_NANOS))
                .isEqualTo(2_000_000L);
        assertThat(tunables.revision()).isEqualTo(1L);

        // Unknown keys, malformed values, and out-of-range values all reject.
        assertThat(tunables.applyChanges(Map.of("bogus.key", "1"), 1L).committed()).isFalse();
        assertThat(tunables.applyChanges(Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS, "abc"), 1L)
                .committed()).isFalse();
        assertThat(tunables.applyChanges(Map.of(
                com.storynpcs.admin.RuntimeTunables.DIALOGUE_TOKEN_EXPIRY_TICKS, "99999999"), 1L)
                .committed()).isFalse();
        assertThat(tunables.revision()).isEqualTo(1L);
    }

    @Test
    void playerDataScopeDistinguishesSelfAndAdmin() {
        UUID me = UUID.randomUUID(), other = UUID.randomUUID();
        assertThat(PlayerDataScope.SELF.permits(me, me)).isTrue();
        assertThat(PlayerDataScope.SELF.permits(me, other)).isFalse();
        assertThat(PlayerDataScope.ADMIN.permits(me, other)).isTrue();
        assertThat(PlayerDataScope.SELF.permits(null, me)).isFalse();
    }

    @Test
    void configTransactionIsAtomic() {
        ConfigTransaction tx = new ConfigTransaction(Map.of("difficulty", "normal"), 5,
                cfg -> {
                    var r = new com.storynpcs.domain.common.ValidationResult();
                    if (!Set.of("peaceful", "normal", "hard").contains(cfg.get("difficulty"))) {
                        r.addError("BAD_DIFFICULTY", "invalid difficulty");
                    }
                    return r;
                });

        tx.stage(Map.of("difficulty", "insane"));
        var bad = tx.commit(5);
        assertThat(bad.committed()).isFalse();
        assertThat(tx.current()).containsEntry("difficulty", "normal"); // rolled back

        ConfigTransaction tx2 = new ConfigTransaction(Map.of("difficulty", "normal"), 5,
                cfg -> new com.storynpcs.domain.common.ValidationResult());
        tx2.stage(Map.of("difficulty", "hard"));
        assertThat(tx2.commit(4).committed()).isFalse(); // stale revision
        assertThat(tx2.stage()).isEqualTo(ConfigTransaction.Stage.ROLLED_BACK);

        ConfigTransaction tx3 = new ConfigTransaction(Map.of("difficulty", "normal"), 5,
                cfg -> new com.storynpcs.domain.common.ValidationResult());
        tx3.stage(Map.of("difficulty", "hard"));
        var ok = tx3.commit(5);
        assertThat(ok.committed()).isTrue();
        assertThat(ok.newRevision()).isEqualTo(6);
        assertThat(tx3.current()).containsEntry("difficulty", "hard");
    }
}
