package com.storynpcs.entity;

import com.storynpcs.StoryNpcs;
import com.storynpcs.StoryNpcsAccess;
import com.storynpcs.ai.NpcFollowFormationGoal;
import com.storynpcs.ai.NpcPatrolGoal;
import com.storynpcs.ai.NpcReturnToStartGoal;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.domain.role.follower.FollowerGroup;
import com.storynpcs.domain.role.follower.FollowerRole;
import com.storynpcs.domain.role.follower.FormationType;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.runtime.actor.ActorLifecycleService;
import com.storynpcs.service.DialogueView;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;

import java.util.Optional;

public class StoryNpcEntity extends PathfinderMob {

    private static final EntityDataAccessor<String> DEFINITION_ID =
            SynchedEntityData.defineId(StoryNpcEntity.class, EntityDataSerializers.STRING);

    /** Synced authored animation stance ordinal — renderers read the authored resting pose. */
    private static final EntityDataAccessor<Integer> ANIMATION_STANCE =
            SynchedEntityData.defineId(StoryNpcEntity.class, EntityDataSerializers.INT);

    /** Synced active emote ordinal ({@link com.storynpcs.domain.npc.NpcEmote}); runtime-only. */
    private static final EntityDataAccessor<Integer> EMOTE =
            SynchedEntityData.defineId(StoryNpcEntity.class, EntityDataSerializers.INT);

    /** Synced emote progress numerator (ticks elapsed); clients derive progress against EMOTE_TICKS_TOTAL. */
    private static final EntityDataAccessor<Integer> EMOTE_ELAPSED =
            SynchedEntityData.defineId(StoryNpcEntity.class, EntityDataSerializers.INT);

    /** Synced emote duration denominator (total ticks). */
    private static final EntityDataAccessor<Integer> EMOTE_TICKS_TOTAL =
            SynchedEntityData.defineId(StoryNpcEntity.class, EntityDataSerializers.INT);

    private final StoryNpcState state = new StoryNpcState();
    private final com.storynpcs.ai.combat.ThreatManager threatManager = new com.storynpcs.ai.combat.ThreatManager();
    private final com.storynpcs.ai.combat.NpcAbilityController abilityController =
            new com.storynpcs.ai.combat.NpcAbilityController(this.random::nextDouble);
    private BlockPos startPosition;
    /**
     * HIDE-defeat state: {@code >0} counts down to reappearance,
     * {@code <0} means hidden indefinitely, {@code 0} means not hidden.
     */
    private int hiddenDefeatTicksLeft = 0;
    /** Game time of the last threat-manager tick pulse (for real elapsed accounting). */
    private long lastThreatTickTime = Long.MIN_VALUE;
    /** Equipment items already warned as unresolvable — dedups refresh spam. */
    private final java.util.Set<String> unresolvableEquipmentItems = new java.util.HashSet<>();
    /**
     * Set once the authored drop table rolled on a real death and persisted in
     * NBT — a corpse reloaded mid-window re-resolves defeat for cleanup only;
     * authored drops and events must never fire twice (duplication exploit).
     */
    private boolean deathDropsResolved = false;
    /** Whether the entity was already invulnerable before entering hidden defeat. */
    private boolean wasInvulnerableBeforeHide = false;
    /** Whether the entity already had noPhysics before entering hidden defeat. */
    private boolean wasNoPhysicsBeforeHide = false;
    private FollowerRole followerRole;
    private boolean loadingSavedData;
    /**
     * True once this entity has been restored from NBT. Disk-loaded entities
     * carry persisted health; {@link #applyDefinition()} must never heal them
     * back to authored max on re-projection — health is runtime state.
     */
    private boolean loadedFromDisk;
    /** Revision read back from NBT — defaults to 0 for pre-marker saves. */
    private int loadedDataRevision = 0;

    /** The persisted data revision this entity was loaded at; 0 for pre-marker saves. */
    public int getLoadedDataRevision() { return loadedDataRevision; }
    /** Server-authoritative emote lifecycle; the three EMOTE_* accessors mirror it to clients. */
    private final com.storynpcs.domain.npc.NpcEmoteState emoteState =
            new com.storynpcs.domain.npc.NpcEmoteState();

    public StoryNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.threatManager.setAggroEventSink(this::publishAggroChange);
    }

    /** Every threat-target transition is observable with its reason. */
    private void publishAggroChange(java.util.UUID target, boolean isAggro, String reason) {
        if (this.level() == null || this.level().isClientSide) return;
        StoryNpcs mod = StoryNpcsAccess.mod(this);
        if (mod == null || mod.getEventPublisher() == null) return;
        NamespacedId npcId = state.resolveDefinition(mod.getRegistry())
                .map(NpcDefinition::getId).orElse(null);
        if (npcId == null) return;
        mod.getEventPublisher().publish(
                new com.storynpcs.api.event.NpcAggroChangeEvent(npcId, target, isAggro, reason));
        // P9-2: target hook on aggro acquisition; de-aggro stays event-only
        // (disclosed in ScriptHookMatrix).
        if (isAggro) {
            dispatchScriptHook(com.storynpcs.script.ScriptHook.TARGET, null);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.STEP_HEIGHT, 1.0625D);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new com.storynpcs.ai.pathing.StoryNpcPathNavigator(this, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // B7 "panic": hurt panic outranks combat — MOVE-flag conflict with the
        // melee goal resolves to the earlier-registered (panic) goal.
        this.goalSelector.addGoal(1, new com.storynpcs.ai.NpcAuthoredBehaviorGoals.PanicOnHurtGoal(this, 1.2D));
        // B7 "avoid": selector-matched flight outranks engagement.
        this.goalSelector.addGoal(1, new com.storynpcs.ai.combat.NpcAvoidTargetsGoal(this));
        this.goalSelector.addGoal(2, new com.storynpcs.ai.combat.NpcMeleeAttackGoal(this, 1.25D));
        this.goalSelector.addGoal(3, new NpcFollowFormationGoal(this, 1.0D, 1.35D, 1.75F, 24.0F));
        this.goalSelector.addGoal(4, new NpcPatrolGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new NpcReturnToStartGoal(this, 1.0D));
        // B6 vocabulary goals — authored flags gate each wrapped primitive.
        this.goalSelector.addGoal(5, new com.storynpcs.ai.NpcAuthoredBehaviorGoals.SeekShadeGoal(this));
        this.goalSelector.addGoal(5, new com.storynpcs.ai.NpcAuthoredBehaviorGoals.DoorBustGoal(this));
        this.goalSelector.addGoal(6, new com.storynpcs.ai.NpcAuthoredBehaviorGoals.ShelterIndoorsGoal(this));
        // B6 "watch closest" — authored flag gates the vanilla look-at.
        this.goalSelector.addGoal(6, new com.storynpcs.ai.NpcAuthoredBehaviorGoals.WatchClosestGoal(this, 8.0F));
        this.goalSelector.addGoal(7, new NpcWanderingStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // P3-3: sight-based target acquisition for attackOnSight definitions —
        // feeds chosen targets through the canonical threat pipeline.
        this.targetSelector.addGoal(1, new com.storynpcs.ai.combat.NpcAttackOnSightGoal(this));
        // P3-2: authored ranged attack — fires volleys at the threat target
        // inside the authored range/LOS envelope. Flagless, like acquisition.
        this.targetSelector.addGoal(2, new com.storynpcs.ai.combat.NpcRangedAttackGoal(this));
    }

    private static class NpcWanderingStrollGoal extends WaterAvoidingRandomStrollGoal {
        private final StoryNpcEntity npc;

        public NpcWanderingStrollGoal(StoryNpcEntity mob, double speedModifier) {
            super(mob, speedModifier);
            this.npc = mob;
        }

        @Override
        public boolean canUse() {
            // P4-1: pathing-disabled tiers never start a stroll — dormant
            // wanderers stand still instead of spending path finds.
            if (npc.simulationCapabilityPeriod(
                    com.storynpcs.sim.SimulationScheduler.Capability.PATHING) < 0) {
                return false;
            }
            var defOpt = npc.getDefinition();
            if (defOpt.isEmpty()) return false;
            var ai = defOpt.get().getAi();
            if (ai == null || ai.getMovementType() != com.storynpcs.domain.npc.NpcAi.MovementType.WANDERING) {
                return false;
            }
            return super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return npc.simulationCapabilityPeriod(
                    com.storynpcs.sim.SimulationScheduler.Capability.PATHING) >= 0
                    && super.canContinueToUse();
        }

        @Override
        public void stop() {
            npc.getNavigation().stop();
            super.stop();
        }
    }

    public com.storynpcs.ai.combat.ThreatManager getThreatManager() {
        return threatManager;
    }

    /**
     * P4-1 goal-facing accessor for the simulation-tier budget: the effective
     * period in ticks for {@code capability} at this entity's current tier,
     * {@code -1} when the capability is disabled, {@code 1} when the scheduler
     * is absent or the entity is unevaluated (full fidelity). Consumers must
     * throttle on elapsed game ticks — see
     * {@link com.storynpcs.sim.SimulationScheduler#capabilityPeriod}.
     */
    public int simulationCapabilityPeriod(
            com.storynpcs.sim.SimulationScheduler.Capability capability) {
        var mod = com.storynpcs.StoryNpcsAccess.mod(this);
        var sim = mod != null ? mod.getSimulationScheduler() : null;
        return sim == null ? 1 : sim.capabilityPeriod(getUUID(), capability);
    }

    @Override
    public void aiStep() {
        // Hidden-defeat statues freeze: no super tick, no goals, no drift,
        // no portal progress — only the respawn countdown advances
        // (negative = hidden indefinitely).
        if (!this.level().isClientSide && hiddenDefeatTicksLeft != 0) {
            if (this.getHealth() <= 0.0F) {
                // tickDeath() runs in tick(), outside this freeze — a statue
                // left at 0 HP (data merge, corrupt load) would corpse-remove.
                this.setHealth(Math.min(1.0f, this.getMaxHealth()));
            }
            this.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            this.getNavigation().stop();
            if (hiddenDefeatTicksLeft > 0) {
                hiddenDefeatTicksLeft--;
                if (hiddenDefeatTicksLeft == 0) {
                    reappearFromHiddenDefeat();
                }
            }
            return;
        }
        // Fail-safe for entities forced to 0 HP without resolving die()
        // (external setHealth / corrupt NBT): LivingEntity.isAlive() is
        // health-based in 1.21.1, so the guard is the `dead` flag — a husk
        // (dead=false, health=0) re-resolves through the defeat contract
        // instead of standing inert forever; an entity already dying
        // (dead=true) is left to vanilla corpse handling.
        if (!this.level().isClientSide && !this.dead && !this.isRemoved()
                && this.getHealth() <= 0.0F) {
            die(this.damageSources().generic());
            return;
        }
        super.aiStep();
        if (this.level().isClientSide) {
            return;
        }
        // P9-2: script init fires once on the first live server tick, then the
        // per-tick hook — both budgeted dispatches, failures quarantined.
        if (!scriptInitFired) {
            scriptInitFired = true;
            dispatchScriptHook(com.storynpcs.script.ScriptHook.INIT, null);
        }
        dispatchScriptHook(com.storynpcs.script.ScriptHook.TICK, null);
        if (emoteState.tick()) {
            pushEmoteSync();
        } else if (emoteState.isActive()) {
            this.entityData.set(EMOTE_ELAPSED,
                    emoteState.totalTicks() - emoteState.remainingTicks());
        }
        long now = this.level().getGameTime();

        // P4-1: simulation-tier capability gates. Unevaluated actors (null
        // state — tier scan runs every 20 ticks) keep full fidelity; evaluated
        // actors degrade per the resolved tier budgets.
        var sim = com.storynpcs.StoryNpcsAccess.mod(this) != null
                ? com.storynpcs.StoryNpcsAccess.mod(this).getSimulationScheduler() : null;
        boolean sensingDue = sim == null || sim.stateOf(this.getUUID()) == null
                || sim.shouldRun(this.getUUID(),
                        com.storynpcs.sim.SimulationScheduler.Capability.SENSING, now);
        boolean animationDue = sim == null || sim.stateOf(this.getUUID()) == null
                || sim.shouldRun(this.getUUID(),
                        com.storynpcs.sim.SimulationScheduler.Capability.ANIMATION, now);

        // Threat calm-down runs on the sensing grid, throttled to ~20 game
        // ticks per pulse by the elapsed-time marker itself. Gating on
        // tickCount % 20 instead would intersect a per-entity phase with the
        // scheduler's global-grid sensing window — for NEARBY/DISTANT tiers
        // most phases never coincide, so the timer would stall forever
        // (self-locking aggro: in-combat actors can never leave DISTANT).
        if (sensingDue && (lastThreatTickTime == Long.MIN_VALUE
                || now - lastThreatTickTime >= 20)) {
            // The sensing budget can defer pulses — pass the real elapsed
            // ticks so the authored calm-down duration stays in game ticks
            // instead of stretching with the call cadence.
            int elapsed = lastThreatTickTime == Long.MIN_VALUE
                    ? 20 : (int) Math.min(20_000, now - lastThreatTickTime);
            lastThreatTickTime = now;
            this.threatManager.tick(5, elapsed);
        }

        // #147: authored combat abilities — UPDATE trigger. The controller
        // enforces its own per-entity minimum update period plus per-ability
        // cooldowns, so the per-tick cost is a map lookup when nothing is due.
        tickAbilities(now);

        if (this.tickCount % 20 == 0) {
            if (animationDue) {
                updateBossBar();
            }
            tickCompanionWages(now);
            tickCompanionEffects(now);
            tickSocialRoles(now);
            tickAuthoredRegen();
        }

        // P6-4: per-tick due check — each job honors its own tickPeriod budget.
        if (jobInstance != null && jobInstance.shouldRun(now)
                && !companionPaused) {
            com.storynpcs.runtime.job.NpcJobRuntime.run(this, jobInstance);
            jobInstance.markRan(now);
        }
    }

    /**
     * Authored combat abilities (#147): evaluates UPDATE-triggered abilities
     * against the current threat target. The controller self-throttles to
     * {@code MIN_UPDATE_PERIOD_TICKS}; absent abilities or no live target
     * short-circuit before any outcome computation.
     */
    private void tickAbilities(long now) {
        var abilities = getDefinition().map(NpcDefinition::getAbilities).orElse(java.util.List.of());
        if (abilities.isEmpty()) {
            return;
        }
        // Same tier budget as melee/ranged combat — a DORMANT NPC must not
        // keep pulling or teleporting toward a stale threat target.
        if (simulationCapabilityPeriod(
                com.storynpcs.sim.SimulationScheduler.Capability.COMBAT) <= 0) {
            return;
        }
        var targetUuid = threatManager.getCurrentTarget();
        if (targetUuid.isEmpty() || !(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getEntity(targetUuid.get()) instanceof LivingEntity victim) || !victim.isAlive()) {
            return;
        }
        applyAbilityOutcomes(
                this.abilityController.onUpdate(abilities, abilityEventInput(victim, 0, now)),
                victim);
    }

    /**
     * ATTACK-triggered abilities after a landed hit (melee goal or projectile
     * impact). Server-side only; callers are already in server code paths.
     */
    public void fireAbilitiesOnAttack(LivingEntity target) {
        if (this.level().isClientSide || target == null || !target.isAlive()) {
            return;
        }
        var abilities = getDefinition().map(NpcDefinition::getAbilities).orElse(java.util.List.of());
        if (abilities.isEmpty()) {
            return;
        }
        applyAbilityOutcomes(
                this.abilityController.onAttack(abilities,
                        abilityEventInput(target, 0, this.level().getGameTime())),
                target);
    }

    private com.storynpcs.ai.combat.NpcAbilityController.EventInput abilityEventInput(
            LivingEntity actor, double damage, long gameTime) {
        var def = getDefinition().orElse(null);
        return new com.storynpcs.ai.combat.NpcAbilityController.EventInput(
                def != null ? def.getId() : null,
                def != null && def.getDisplay() != null ? def.getDisplay().getName() : "StoryNPC",
                getHealth(), Math.max(1.0, getMaxHealth()),
                getX(), getY(), getZ(),
                actor.getX(), actor.getY(), actor.getZ(),
                damage, gameTime, actor.getUUID(), actor instanceof ServerPlayer,
                threatManager.getStrikes(actor.getUUID()), resolveActorStanding(actor));
    }

    /**
     * Resolves the attacker's faction standing toward this NPC's faction for
     * {@code FACTION_STANDING} ability conditions: players read progression
     * faction scores against authored thresholds; NPC actors resolve through
     * the inter-faction relationship matrix. Null when unresolvable.
     */
    private com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing resolveActorStanding(
            LivingEntity actor) {
        var mod = StoryNpcsAccess.mod(this);
        if (mod == null) {
            return null;
        }
        var ownFactionId = getState().getFactionId(mod.getRegistry()).orElse(null);
        if (actor instanceof ServerPlayer player) {
            var progressionRepo = mod.getProgressionRepository();
            if (progressionRepo == null || ownFactionId == null) {
                return null;
            }
            var faction = mod.getRegistry().getFaction(ownFactionId).orElse(null);
            int defaultPoints = faction != null ? faction.getDefaultPoints() : 1000;
            int score = progressionRepo.getOrCreate(player.getUUID())
                    .getFactionScore(ownFactionId, defaultPoints);
            if (score < (faction != null ? faction.getHostileThreshold() : 500)) {
                return com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.HOSTILE;
            }
            if (score >= (faction != null ? faction.getFriendlyThreshold() : 1500)) {
                return com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.FRIENDLY;
            }
            return com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.NEUTRAL;
        }
        if (actor instanceof StoryNpcEntity otherNpc && ownFactionId != null) {
            var otherFactionId = otherNpc.getState().getFactionId(mod.getRegistry()).orElse(null);
            if (otherFactionId == null) {
                return null;
            }
            if (ownFactionId.equals(otherFactionId)) {
                return com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.FRIENDLY;
            }
            var relationship = com.storynpcs.domain.ai.FactionRelationshipProvider
                    .fromFactions(mod.getRegistry().getAllFactions())
                    .relationship(ownFactionId, otherFactionId);
            return switch (relationship) {
                case HOSTILE -> com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.HOSTILE;
                case FRIENDLY -> com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.FRIENDLY;
                default -> com.storynpcs.domain.rule.condition.FactionStandingCondition.Standing.NEUTRAL;
            };
        }
        return null;
    }

    /** Applies controller outcomes to real entities; DAMAGE_MULTIPLIER is consumed by the hurt() caller. */
    private void applyAbilityOutcomes(
            java.util.List<com.storynpcs.ai.combat.NpcAbilityController.AbilityOutcome> outcomes,
            LivingEntity victim) {
        for (var outcome : outcomes) {
            switch (outcome.kind()) {
                case TARGET_VELOCITY -> {
                    victim.setDeltaMovement(victim.getDeltaMovement()
                            .add(outcome.dx(), outcome.dy(), outcome.dz()));
                    victim.hurtMarked = true;
                }
                case TARGET_SLOWNESS -> victim.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN, outcome.durationTicks(), outcome.amplifier()),
                        this);
                case SELF_TELEPORT -> teleportTo(outcome.dx(), outcome.dy(), outcome.dz());
                case BONUS_DAMAGE -> {
                    // This runs immediately after the triggering hit landed,
                    // so the victim's invulnerability window is already full.
                    // Vanilla hurt() would clamp the bonus to
                    // max(0, bonus - lastHurt) — the authored bonus damage
                    // must land additively, so clear the window first. Same
                    // reason applyAuthoredAreaDamage skips the direct victim.
                    victim.invulnerableTime = 0;
                    victim.hurt(damageSources().mobAttack(this), (float) outcome.amount());
                }
                case DAMAGE_MULTIPLIER -> { } // consumed by hurt() before damage is applied
            }
        }
    }

    /**
     * Authored regeneration (P3-2 stats contract): once per second — the
     * combat rate applies while a threat target is engaged, the idle rate
     * otherwise. Bounded to [0, 1000] HP/s by the schema clamps.
     */
    private void tickAuthoredRegen() {
        if (!isAlive() || getHealth() >= getMaxHealth()) {
            return;
        }
        var stats = getDefinition().map(d -> d.getStats()).orElse(null);
        if (stats == null) {
            return;
        }
        double rate = threatManager.getCurrentTarget().isPresent()
                ? stats.getCombatRegenPerSecond()
                : stats.getHealthRegenPerSecond();
        if (rate > 0) {
            heal((float) rate);
        }
    }

    /**
     * P6-5: apply the companion's stage multiplier + talent effects as
     * transient attribute modifiers, refreshed whenever the resolved
     * projection changes (stage advance, talent re-auth, profile remove).
     * Runs on the 20-tick social cadence — projection math is a bounded scan
     * (≤16 stages, ≤32 talents) and the signature check is a hash compare.
     */
    private void tickCompanionEffects(long now) {
        var projection = companionProfile == null
                ? com.storynpcs.domain.companion.CompanionEffects.Projection.NONE
                : com.storynpcs.domain.companion.CompanionEffects.summarize(
                        companionProfile, companionHiredTick < 0 ? 0 : now - companionHiredTick);
        int signature = projection.hashCode();
        if (signature == lastCompanionEffectSignature) {
            return;
        }
        lastCompanionEffectSignature = signature;
        applyCompanionModifier(COMPANION_STAGE_DAMAGE_MOD, Attributes.ATTACK_DAMAGE,
                projection.stageMultiplier() - 1.0,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyCompanionModifier(COMPANION_STAGE_HEALTH_MOD, Attributes.MAX_HEALTH,
                projection.stageMultiplier() - 1.0,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyCompanionModifier(COMPANION_TALENT_DAMAGE_MOD, Attributes.ATTACK_DAMAGE,
                projection.damageBonus(),
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        applyCompanionModifier(COMPANION_TALENT_ARMOR_MOD, Attributes.ARMOR,
                projection.armorBonus(),
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        applyCompanionModifier(COMPANION_TALENT_SPEED_MOD, Attributes.MOVEMENT_SPEED,
                projection.speedBonusFraction(),
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        if (this.getHealth() > this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }
    }

    private static final net.minecraft.resources.ResourceLocation COMPANION_STAGE_DAMAGE_MOD =
            net.minecraft.resources.ResourceLocation.parse("storynpcs:companion_stage_damage");
    private static final net.minecraft.resources.ResourceLocation COMPANION_STAGE_HEALTH_MOD =
            net.minecraft.resources.ResourceLocation.parse("storynpcs:companion_stage_health");
    private static final net.minecraft.resources.ResourceLocation COMPANION_TALENT_DAMAGE_MOD =
            net.minecraft.resources.ResourceLocation.parse("storynpcs:companion_talent_damage");
    private static final net.minecraft.resources.ResourceLocation COMPANION_TALENT_ARMOR_MOD =
            net.minecraft.resources.ResourceLocation.parse("storynpcs:companion_talent_armor");
    private static final net.minecraft.resources.ResourceLocation COMPANION_TALENT_SPEED_MOD =
            net.minecraft.resources.ResourceLocation.parse("storynpcs:companion_talent_speed");

    private void applyCompanionModifier(net.minecraft.resources.ResourceLocation id,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            double amount,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
        var attr = this.getAttribute(attribute);
        if (attr == null) {
            return;
        }
        var existing = attr.getModifier(id);
        if (Math.abs(amount) < 1.0e-9) {
            if (existing != null) {
                attr.removeModifier(id);
            }
            return;
        }
        if (existing != null && Math.abs(existing.amount() - amount) < 1.0e-9) {
            return;
        }
        attr.addOrUpdateTransientModifier(
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(id, amount, operation));
    }

    /** Effective carried-item capacity for this companion (P6-5; wave-2 UI consumes it). */
    public int companionCarryCapacity() {
        if (companionProfile == null) {
            return com.storynpcs.domain.companion.CompanionEffects.BASE_CARRY_CAPACITY;
        }
        var projection = com.storynpcs.domain.companion.CompanionEffects.summarize(
                companionProfile, companionHiredTick < 0 ? 0 : tickCount - companionHiredTick);
        return com.storynpcs.domain.companion.CompanionEffects.effectiveCarryCapacity(projection);
    }

    /** Companion wage charge + insufficient-funds/unload policy handling. */
    private void tickCompanionWages(long now) {
        if (companionProfile == null || followerRole == null
                || followerRole.getOwnerUuid() == null || !isAlive()) {
            return;
        }
        var mod = com.storynpcs.StoryNpcsAccess.mod(this);
        if (mod == null || mod.getApplicationService() == null) {
            return;
        }
        if (companionHiredTick < 0) {
            companionHiredTick = now; // first observed owner tick = hire edge
        }
        var outcome = mod.getApplicationService().chargeCompanionWage(
                followerRole.getOwnerUuid(), this.getUUID(),
                companionProfile, companionWageLedger, companionHiredTick, now);
        if (outcome == lastWageOutcome) {
            return; // only message on transitions — never spam the owner
        }
        var previous = lastWageOutcome;
        lastWageOutcome = outcome;
        var owner = this.level().getServer() != null
                ? this.level().getServer().getPlayerList().getPlayer(followerRole.getOwnerUuid())
                : null;
        switch (outcome) {
            case CHARGED -> {
                companionPaused = false;
                if (previous == com.storynpcs.service.StoryNpcsApplicationService
                        .CompanionWageOutcome.INSUFFICIENT_PAUSED && owner != null) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§a" + getName().getString() + " has resumed service — wage paid."), true);
                }
            }
            case INSUFFICIENT_PAUSED -> {
                companionPaused = true;
                if (owner != null) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e" + getName().getString() + " paused service — cannot pay the "
                                    + companionProfile.getWageAmount() + "-emerald wage."), true);
                }
            }
            case INSUFFICIENT_DISMISSED -> {
                companionPaused = false;
                followerRole.setOwnerUuid(null);
                if (owner != null) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c" + getName().getString() + " left your service — wages unpaid."), true);
                }
            }
            case INSUFFICIENT_KEPT -> {
                companionPaused = false;
                if (owner != null) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e" + getName().getString() + " could not be paid but stays by your side."), true);
                }
            }
            case OWNER_OFFLINE_DESPAWN -> {
                companionPaused = false;
                this.discard();
            }
            case OWNER_OFFLINE_PAUSED -> companionPaused = true;
            case PROGRESSION_UNAVAILABLE -> {
                companionPaused = true;
                if (owner != null) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c" + getName().getString() + " paused service — your progression data is unreadable."), true);
                }
            }
            default -> { }
        }
    }

    /** Bard buff pulses and healer scans — bounded by each role's own cadence. */
    private void tickSocialRoles(long now) {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)
                || companionPaused || !isAlive()) {
            return;
        }
        if (bardRole != null && now >= bardNextPlayTick) {
            bardNextPlayTick = now + Math.max(20, bardRole.getCooldownTicks());
            var buffRl = bardRole.getBuffEffect() != null
                    ? net.minecraft.resources.ResourceLocation.tryParse(bardRole.getBuffEffect().toString())
                    : null;
            var buff = buffRl != null
                    ? net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(buffRl).orElse(null)
                    : null;
            if (buff != null) {
                double r2 = bardRole.getEffectRadiusBlocks() * bardRole.getEffectRadiusBlocks();
                var players = serverLevel.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                        this.getBoundingBox().inflate(bardRole.getEffectRadiusBlocks()),
                        p -> p.isAlive() && p.distanceToSqr(this) <= r2);
                for (var p : players) {
                    p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            buff, Math.max(20, bardRole.getPlayDurationTicks()), 0, true, true));
                }
            }
        }
        if (healerRole != null
                && now - healerLastScanTick >= Math.max(1, healerRole.getScanPeriodTicks())) {
            healerLastScanTick = now;
            double r2 = healerRole.getRangeBlocks() * healerRole.getRangeBlocks();
            var box = this.getBoundingBox().inflate(healerRole.getRangeBlocks());
            net.minecraft.world.entity.LivingEntity best = null;
            double bestMissing = 0;
            for (var entity : serverLevel.getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class, box,
                    e -> e.isAlive() && e != this && e.distanceToSqr(this) <= r2)) {
                boolean eligible = switch (healerRole.getTargetPolicy()) {
                    case PLAYERS_ONLY -> entity instanceof net.minecraft.server.level.ServerPlayer;
                    case ALLIES -> entity instanceof net.minecraft.server.level.ServerPlayer
                            || entity instanceof StoryNpcEntity;
                    case ANY_LIVING -> true;
                };
                if (!eligible) continue;
                double missing = entity.getMaxHealth() - entity.getHealth();
                if (missing > bestMissing && missing >= 1.0) {
                    var last = healerCooldowns.get(entity.getUUID());
                    if (last != null && now - last < healerRole.getCooldownTicks()) continue;
                    best = entity;
                    bestMissing = missing;
                }
            }
            if (best != null) {
                best.heal(healerRole.getHealAmount());
                healerCooldowns.put(best.getUUID(), now);
            }
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DEFINITION_ID, "");
        builder.define(ANIMATION_STANCE, com.storynpcs.domain.npc.NpcAi.AnimationStance.NORMAL.ordinal());
        builder.define(EMOTE, com.storynpcs.domain.npc.NpcEmote.NONE.ordinal());
        builder.define(EMOTE_ELAPSED, 0);
        builder.define(EMOTE_TICKS_TOTAL, 0);
    }

    /**
     * Starts an emote for {@code durationTicks} (clamped per
     * {@link com.storynpcs.domain.npc.NpcEmote#MAX_DURATION_TICKS}); passing
     * {@link com.storynpcs.domain.npc.NpcEmote#NONE} stops the active emote.
     * Server-authoritative — client calls are ignored since the lifecycle lives
     * on the server and mirrors down through the synced data.
     */
    public void playEmote(com.storynpcs.domain.npc.NpcEmote emote, int durationTicks) {
        if (this.level().isClientSide) return;
        emoteState.start(emote, durationTicks);
        pushEmoteSync();
    }

    public void stopEmote() {
        playEmote(com.storynpcs.domain.npc.NpcEmote.NONE, 0);
    }

    /** The emote currently rendered — resolved from synced data on the client. */
    public com.storynpcs.domain.npc.NpcEmote activeEmote() {
        if (this.level().isClientSide) {
            int total = this.entityData.get(EMOTE_TICKS_TOTAL);
            int elapsed = this.entityData.get(EMOTE_ELAPSED);
            return total > 0 && elapsed < total
                    ? com.storynpcs.domain.npc.NpcEmote.byOrdinal(this.entityData.get(EMOTE))
                    : com.storynpcs.domain.npc.NpcEmote.NONE;
        }
        return emoteState.current();
    }

    /** Normalized emote progress in {@code [0,1]} for pose interpolation; 0 when inactive. */
    public float emoteProgress() {
        if (this.level().isClientSide) {
            int total = this.entityData.get(EMOTE_TICKS_TOTAL);
            return total <= 0 ? 0f
                    : Math.min(1f, this.entityData.get(EMOTE_ELAPSED) / (float) total);
        }
        return emoteState.progress();
    }

    private void pushEmoteSync() {
        this.entityData.set(EMOTE, emoteState.current().ordinal());
        this.entityData.set(EMOTE_TICKS_TOTAL, emoteState.totalTicks());
        this.entityData.set(EMOTE_ELAPSED,
                emoteState.totalTicks() - emoteState.remainingTicks());
    }

    /** The authored resting animation stance resolved from the definition. */
    public com.storynpcs.domain.npc.NpcAi.AnimationStance animationStance() {
        int ordinal = this.entityData.get(ANIMATION_STANCE);
        var stances = com.storynpcs.domain.npc.NpcAi.AnimationStance.values();
        return ordinal >= 0 && ordinal < stances.length
                ? stances[ordinal] : com.storynpcs.domain.npc.NpcAi.AnimationStance.NORMAL;
    }

    public String getDefinitionId() {
        return this.entityData.get(DEFINITION_ID);
    }

    public void setDefinitionId(String definitionId) {
        this.entityData.set(DEFINITION_ID, definitionId != null ? definitionId : "");
        this.state.setDefinitionId(definitionId);
        if (!loadingSavedData && (this.state.getActorId() == null || this.state.getActorId().isBlank())) {
            // Every fresh entity receives its own logical actor. Clones must not
            // collapse into one actor merely because they share a definition.
            this.state.setActorId("storynpcs:actor/" + this.getUUID());
        }
        applyDefinition();
        if (!loadingSavedData) {
            refreshActorProjection();
        }
    }

    /** Durable logical identity associated with this entity projection. */
    public String getActorId() {
        return state.getActorId();
    }

    public void setActorId(String actorId) {
        state.setActorId(actorId);
        refreshActorProjection();
    }

    private void refreshActorProjection() {
        if (this.level().isClientSide) {
            return;
        }
        StoryNpcs mod = StoryNpcsAccess.mod(this);
        net.minecraft.server.MinecraftServer server = this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel.getServer()
                : null;
        if (mod == null || mod.getActorLifecycleService(server) == null) {
            return;
        }
        try {
            NamespacedId actorId = NamespacedId.of(getActorId());
            NamespacedId definitionId = NamespacedId.of(getDefinitionId());
            var result = mod.getActorLifecycleService(server).bindProjection(actorId, definitionId, this.getUUID());
            if (!result.applied()) {
                StoryNpcs.LOGGER.warn("Could not bind StoryNPC actor {} to projection {}: {}",
                        actorId, this.getUUID(), result.diagnostic());
            }
        } catch (RuntimeException e) {
            StoryNpcs.LOGGER.warn("Could not bind StoryNPC projection {}: {}", this.getUUID(), e.getMessage());
            try {
                NamespacedId actorId = NamespacedId.of(getActorId());
                mod.getActorLifecycleService(server).failProjection(actorId, this.getUUID(), e.getMessage());
            } catch (RuntimeException ignored) {
                // An invalid logical actor ID cannot be represented in a lifecycle event;
                // the warning above remains the diagnostic for malformed legacy NBT.
            }
        }
    }

    public BlockPos getStartPosition() {
        if (startPosition == null) {
            // Never pin a below-world position — a lazy snapshot taken while
            // the entity falls out of the world would make every future
            // return-to-start unreachable.
            BlockPos here = this.blockPosition();
            if (here.getY() >= this.level().dimensionType().minY()) {
                startPosition = here;
            }
        }
        return startPosition;
    }

    public void setStartPosition(BlockPos startPosition) {
        this.startPosition = startPosition;
    }

    public StoryNpcState getState() {
        return state;
    }

    public Optional<NpcDefinition> getDefinition() {
        if (state == null) return Optional.empty();
        var mod = StoryNpcsAccess.mod(this);
        return mod != null ? state.resolveDefinition(mod.getRegistry()) : Optional.empty();
    }

    /** One-shot init-hook latch — scripts init on the first live server tick. */
    private boolean scriptInitFired = false;

    /**
     * P9-2: dispatch an entity hook to the scripts bound in this NPC's
     * definition. Every dispatch is metered+quarantined by the script runtime;
     * this seam adds only the context assembly. A missing mod/runtime or a
     * definition without bound scripts is a silent no-op — scripting is an
     * optional authored layer, never an entity invariant.
     */
    private void dispatchScriptHook(com.storynpcs.script.ScriptHook hook,
                                    net.minecraft.server.level.ServerPlayer player) {
        var mod = StoryNpcsAccess.mod(this);
        if (mod == null || mod.getApplicationService() == null) {
            return;
        }
        var definition = getDefinition().orElse(null);
        if (definition == null || definition.getScripts().isEmpty()) {
            return;
        }
        try {
            mod.getScriptRuntime().dispatchFor(hook,
                    com.storynpcs.script.ScriptRuntime.entityContext(hook,
                            getDefinitionId(),
                            player == null ? null : player.getUUID().toString(),
                            level().dimension().location().toString()),
                    definition.getScripts());
        } catch (RuntimeException dispatchFailure) {
            // The scheduler isolates script failures itself; this guard keeps a
            // lookup/runtime hiccup from breaking the entity tick path.
            StoryNpcs.LOGGER.debug("Script hook {} dispatch failed for {}: {}",
                    hook, getDefinitionId(), dispatchFailure.toString());
        }
    }

    private final com.storynpcs.domain.npc.DisplayProjectionCache displayProjectionCache =
            new com.storynpcs.domain.npc.DisplayProjectionCache();

    /**
     * The resolved display projection for this entity, or null when the
     * definition/display is unavailable. Content-fingerprinted so definition
     * edits re-resolve while identical frames reuse the cached projection.
     */
    public com.storynpcs.domain.npc.DisplayProjection displayProjection() {
        Optional<NpcDefinition> def = getDefinition();
        if (def.isEmpty() || def.get().getDisplay() == null) return null;
        return displayProjectionCache.projectionFor(getUUID(), def.get().getDisplay());
    }

    /**
     * Hitbox dimensions are applied through {@link StoryNpcHitboxHandler}, which
     * projects the display contract via {@code EntityEvent.Size}; statue mode
     * (hitboxState 1) additionally disables pushing.
     */
    public com.storynpcs.domain.npc.DisplayProjection.ProjectedHitbox projectedHitbox() {
        var projection = displayProjection();
        return projection != null ? projection.hitbox() : null;
    }

    @Override
    public boolean isPushable() {
        Optional<NpcDefinition> def = getDefinition();
        if (def.isPresent() && def.get().getDisplay() != null
                && def.get().getDisplay().getHitboxState() == 1) {
            return false;
        }
        return super.isPushable();
    }

    public static void onLivingKnockback(
            net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof StoryNpcEntity npc) || npc.level().isClientSide) {
            return;
        }
        npc.getDefinition()
                .map(NpcDefinition::getStats)
                .map(stats -> stats.getResistances())
                .ifPresent(resistances -> event.setStrength(
                        resistances.scaleKnockback(event.getStrength())));
    }

    public void applyDefinition() {
        var mod = StoryNpcsAccess.mod(this);
        if (mod == null) return;

        // The whole nameplate projection is hidden-guarded: re-writing
        // customName while hidden would re-arm the crosshair-pick plate
        // (shouldShowName renders hasCustomName on invisible entities).
        // Reappearance re-applies the authored name.
        if (!isHiddenDefeat()) {
            state.getDisplayName(mod.getRegistry()).ifPresent(name -> {
                this.setCustomName(Component.literal(name));
                this.setCustomNameVisible(true);
            });
        }

        state.getStats(mod.getRegistry()).ifPresent(stats -> {
            var maxHealthAttr = this.getAttribute(Attributes.MAX_HEALTH);
            // A resolved-corpse husk reloading mid-window must stay at 0 HP —
            // restoring health here would resurrect it carrying
            // deathDropsResolved, silently eating the next real death's
            // authored drops and events.
            if (maxHealthAttr != null && stats.getMaxHealth() > 0) {
                maxHealthAttr.setBaseValue(stats.getMaxHealth());
                if (!deathDropsResolved && !loadedFromDisk) {
                    this.setHealth((float) stats.getMaxHealth());
                }
            }
            var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null && stats.getMovementSpeed() > 0) {
                speedAttr.setBaseValue(stats.getMovementSpeed());
            }
            var damageAttr = this.getAttribute(Attributes.ATTACK_DAMAGE);
            if (damageAttr != null) {
                damageAttr.setBaseValue(stats.getAttackDamage());
            }
            var knockbackAttr = this.getAttribute(Attributes.ATTACK_KNOCKBACK);
            if (knockbackAttr != null && stats.getMelee() != null) {
                knockbackAttr.setBaseValue(stats.getMelee().getKnockbackStrength());
            }
            var followAttr = this.getAttribute(Attributes.FOLLOW_RANGE);
            if (followAttr != null && stats.getAggroRange() > 0) {
                followAttr.setBaseValue(stats.getAggroRange());
            }
            // Unconditional: an authored 0 must clear a previously applied
            // reward, not leave the stale value armed on the live entity.
            this.xpReward = Math.max(0, stats.getXpReward());
        });

        state.resolveDefinition(mod.getRegistry()).ifPresent(def -> {
            if (def.getAi() != null) {
                if (this.getNavigation() instanceof GroundPathNavigation groundNav) {
                    // B6 door vocabulary: open when doorInteract or doorBust —
                    // busting still requires the navigator to route to doors.
                    groundNav.setCanOpenDoors(def.getAi().isDoorInteract() || def.getAi().isDoorBust());
                }
                this.setPathfindingMalus(PathType.WATER, def.getAi().isAvoidWater() ? -1.0F : 0.0F);
                this.threatManager.setAggroDurationTicks(def.getAi().getAggroDurationTicks());
                if (!isHiddenDefeat()) {
                    // A hidden statue re-asserts its own posture — a definition
                    // refresh must not visibly resurface it mid-countdown.
                    applyAnimationStance(def.getAi().getAnimationStance());
                }
            }
            var display = def.getDisplay();
            if (display != null) {
                // Display flags are authoritative entity state: glowing outline
                // and hidden visibility come from the projection contract.
                // Neither may resurface a hidden-defeat statue mid-countdown —
                // glowing renders on invisible entities, so both fields are
                // skipped while hidden (the statue asserts its own posture).
                if (!isHiddenDefeat()) {
                    this.setGlowingTag(display.isOverlayGlowing());
                    this.setInvisible(display.getVisibility() == 1);
                }
            }

            // P3-4: authored equipment projects onto the live equipment slots —
            // armor renders/protects and hand items are visible. Drop chances
            // are pinned to 0 so vanilla never double-dips the authored drop
            // table; drops are exclusively the authored contract.
            if (def.getInventory() != null) {
                applyEquipmentProjection(def.getInventory());
            }

            // P6 bindings: scheduled job instance + social/companion profiles.
            var previousJob = this.jobInstance;
            this.jobInstance = null;
            if (def.getJob() != null) {
                try {
                    // JobInstance construction re-validates the config — an
                    // invalid job that bypassed load validation fails closed
                    // here instead of breaking the entity spawn path.
                    this.jobInstance = new com.storynpcs.domain.job.JobInstance(
                            this.getUUID(), def.getJob());
                    publishJobTransition(this.jobInstance, null);
                } catch (RuntimeException jobFailure) {
                    com.storynpcs.StoryNpcs.LOGGER.warn(
                            "NPC {} carries an invalid job config — job disabled: {}",
                            def.getId(), jobFailure.getMessage());
                }
            }
            if (previousJob != null && previousJob.getState()
                    != com.storynpcs.domain.job.JobInstance.State.STOPPED) {
                // Stopped whether the job was removed or replaced — a swapped
                // instance must not linger as an orphan RUNNING marker (also
                // releases its transient builder-schematic cache).
                var preStop = previousJob.getState();
                previousJob.stop();
                publishJobTransition(previousJob, preStop);
            }
            this.companionProfile = def.getCompanion();
            this.bardRole = def.getBard();
            this.healerRole = def.getHealer();
            this.postmanRole = def.getPostman();
            if (this.companionProfile == null) {
                this.companionPaused = false;
                this.lastWageOutcome = null;
            }

            // P9-2: CONVERSATION/PUPPET jobs bind their scriptId to a scheduler
            // script slot — dispatch budgets/quarantine apply at run time.
            var scheduler = mod.getScriptScheduler();
            if (scheduler != null && this.conversationScriptId != null) {
                scheduler.unregister(this.conversationScriptId);
            }
            this.conversationScriptId = null;
            if (scheduler != null && this.jobInstance != null) {
                var scriptId = this.jobInstance.getConfig().getScriptId();
                var type = this.jobInstance.getConfig().getType();
                if (scriptId != null && (type == com.storynpcs.domain.job.JobType.CONVERSATION
                        || type == com.storynpcs.domain.job.JobType.PUPPET)) {
                    var boundScriptId = java.util.UUID.nameUUIDFromBytes(
                            (getUUID() + "|" + scriptId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (scheduler.register(boundScriptId, this.getUUID())) {
                        this.conversationScriptId = boundScriptId;
                    } else {
                        com.storynpcs.StoryNpcs.LOGGER.warn(
                                "Script scheduler capacity reached; disabling script for actor {}", getUUID());
                    }
                }
            }
        });
    }

    /**
     * Projects authored equipment onto the live entity slots: armor and hand
     * items render for clients and feed vanilla combat/attribute behavior.
     * Slots absent from the authored map are cleared so a definition refresh
     * that removes an item strips it. The PROJECTILE slot is authored intent
     * for the ranged contract only — it has no live equipment position.
     */
    private void applyEquipmentProjection(com.storynpcs.domain.npc.NpcInventory inventory) {
        applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                inventory, com.storynpcs.domain.npc.NpcInventory.ItemSlot.HELMET);
        applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                inventory, com.storynpcs.domain.npc.NpcInventory.ItemSlot.CHESTPLATE);
        applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                inventory, com.storynpcs.domain.npc.NpcInventory.ItemSlot.LEGGINGS);
        applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                inventory, com.storynpcs.domain.npc.NpcInventory.ItemSlot.BOOTS);
        applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                inventory, com.storynpcs.domain.npc.NpcInventory.ItemSlot.RIGHT_HAND);
        applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                inventory, com.storynpcs.domain.npc.NpcInventory.ItemSlot.LEFT_HAND);
    }

    private void applyEquipmentSlot(net.minecraft.world.entity.EquipmentSlot slot,
                                    com.storynpcs.domain.npc.NpcInventory inventory,
                                    com.storynpcs.domain.npc.NpcInventory.ItemSlot authored) {
        // Authored drops are the only drop contract — pin the vanilla drop
        // chance to 0 so equipped gear can never double-dip the drop table.
        this.setDropChance(slot, 0.0F);
        var authoredStack = inventory.getEquipment().get(authored);
        if (authoredStack == null) {
            this.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
            return;
        }
        var stack = resolveAuthoredStack(authoredStack, "equipment " + authored);
        this.setItemSlot(slot, stack != null ? stack : net.minecraft.world.item.ItemStack.EMPTY);
    }

    /**
     * Resolves an authored {@code NpcItemStack} to a live ItemStack: item id,
     * bounded count, and — when authored — the server-authored
     * {@code components} payload applied as a {@code DataComponentPatch} (JSON
     * serialized against the registry context, never client-provided NBT).
     * Unknown items resolve null; unparseable component payloads are dropped
     * with a warn-once and the un-enchanted item still applies — a broken
     * payload must not silently delete the authored item.
     */
    public net.minecraft.world.item.ItemStack resolveAuthoredStack(
            com.storynpcs.domain.npc.NpcItemStack authored, String context) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(
                authored.itemId().toString());
        var item = rl != null
                ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null)
                : null;
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            if (unresolvableEquipmentItems.add(context + "=" + authored.itemId())) {
                com.storynpcs.StoryNpcs.LOGGER.warn(
                        "NPC {} {}: unknown item '{}' — skipped",
                        this.getUUID(), context, authored.itemId());
            }
            return null;
        }
        var stack = new net.minecraft.world.item.ItemStack(item,
                Math.min(authored.count(), item.getDefaultMaxStackSize()));
        var components = authored.components();
        if (components != null && !components.isBlank()) {
            try {
                var ops = this.level().registryAccess()
                        .createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                var patch = net.minecraft.core.component.DataComponentPatch.CODEC
                        .decode(ops, com.google.gson.JsonParser.parseString(components))
                        .getOrThrow()
                        .getFirst();
                stack.applyComponents(patch);
            } catch (RuntimeException e) {
                if (unresolvableEquipmentItems.add(context + "=" + authored.itemId() + "#components")) {
                    com.storynpcs.StoryNpcs.LOGGER.warn(
                            "NPC {} {}: invalid components payload dropped: {}",
                            this.getUUID(), context, e.getMessage());
                }
            }
        }
        return stack;
    }

    /**
     * Maps the authored resting stance onto entity state: synced for renderers
     * and applied as a pose where the vanilla vocabulary has an equivalent.
     * A combat/loaded entity keeps its current pose — stance only applies
     * while the NPC is not in a vehicle and not dead.
     */
    private void applyAnimationStance(com.storynpcs.domain.npc.NpcAi.AnimationStance stance) {
        var resolved = stance != null ? stance : com.storynpcs.domain.npc.NpcAi.AnimationStance.NORMAL;
        this.entityData.set(ANIMATION_STANCE, resolved.ordinal());
        if (!this.isAlive() || this.isPassenger() || this.getPose() == net.minecraft.world.entity.Pose.DYING) {
            return;
        }
        switch (resolved) {
            case SNEAKING -> this.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            case SITTING -> this.setPose(net.minecraft.world.entity.Pose.SITTING);
            case LYING -> this.setPose(net.minecraft.world.entity.Pose.SLEEPING);
            case NORMAL, DANCING, AIMING -> this.setPose(net.minecraft.world.entity.Pose.STANDING);
            default -> this.setPose(net.minecraft.world.entity.Pose.STANDING);
        }
    }

    /** The bound conversation/puppet script slot, or null when none is registered. */
    public java.util.UUID conversationScriptId() {
        return conversationScriptId;
    }

    /** Server-side boss bar bound to the display contract; null when disabled. */
    private net.minecraft.server.level.ServerBossEvent bossBar;

    // ---- P6 runtime: scheduled job instance + companion/social-role state ----
    private com.storynpcs.domain.job.JobInstance jobInstance;
    private com.storynpcs.domain.companion.CompanionProfile companionProfile;
    private com.storynpcs.domain.role.social.BardRole bardRole;
    private com.storynpcs.domain.role.social.HealerRole healerRole;
    private com.storynpcs.domain.role.social.PostmanRole postmanRole;
    /** Durable wage ledger — persisted in the Companion NBT tag. */
    private final com.storynpcs.domain.companion.WageLedger companionWageLedger =
            new com.storynpcs.domain.companion.WageLedger();
    private long companionHiredTick = -1;
    private boolean companionPaused;
    /** Signature of the last applied companion effect projection — skips modifier churn when unchanged. */
    private int lastCompanionEffectSignature = -1;
    private com.storynpcs.service.StoryNpcsApplicationService.CompanionWageOutcome lastWageOutcome;
    /** P9-2: script bound through the bounded scheduler for CONVERSATION/PUPPET jobs. */
    private java.util.UUID conversationScriptId;
    private long bardNextPlayTick;
    private long healerLastScanTick = -1;
    /** Per-player ITEM_GIVER cooldowns — insertion-bounded so a crowd cannot grow it. */
    private final java.util.Map<java.util.UUID, Long> itemGiverCooldowns =
            new java.util.LinkedHashMap<>(32, 0.75f, false) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<java.util.UUID, Long> e) {
                    return size() > 128;
                }
            };
    /** Per-target healer cooldowns — same bound. */
    private final java.util.Map<java.util.UUID, Long> healerCooldowns =
            new java.util.LinkedHashMap<>(32, 0.75f, false) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<java.util.UUID, Long> e) {
                    return size() > 128;
                }
            };
    /** Chunks force-loaded by a CHUNK_LOADER job — released on entity removal. */
    private final java.util.Set<net.minecraft.world.level.ChunkPos> jobForcedChunks =
            new java.util.HashSet<>();
    /** Job types already reported as unsupported — one log line per entity per type. */
    private final java.util.Set<com.storynpcs.domain.job.JobType> unsupportedJobsLogged =
            java.util.EnumSet.noneOf(com.storynpcs.domain.job.JobType.class);

    /** Returns true the first time a job type is logged as unsupported. */
    public boolean markUnsupportedJobLogged(com.storynpcs.domain.job.JobType type) {
        return unsupportedJobsLogged.add(type);
    }

    /**
     * Reconciles the display boss-bar contract with the live ServerBossEvent.
     * Runs periodically on the server thread; the event tracks viewers within
     * 64 blocks (the vanilla boss-bar visibility range) and follows the
     * entity's health fraction. Mode 0 removes the bar entirely.
     */
    private void updateBossBar() {
        var projection = displayProjection();
        if (projection == null || projection.bossBarMode() == 0 || !this.isAlive()) {
            if (bossBar != null) {
                bossBar.removeAllPlayers();
                bossBar = null;
            }
            return;
        }
        if (bossBar == null) {
            net.minecraft.world.BossEvent.BossBarColor color;
            try {
                color = net.minecraft.world.BossEvent.BossBarColor.valueOf(projection.bossBarColor().name());
            } catch (IllegalArgumentException e) {
                color = net.minecraft.world.BossEvent.BossBarColor.PINK;
            }
            bossBar = new net.minecraft.server.level.ServerBossEvent(
                    net.minecraft.network.chat.Component.literal(
                            projection.name() != null ? projection.name() : "StoryNPC"),
                    color, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        }
        bossBar.setProgress(this.getMaxHealth() > 0 ? this.getHealth() / this.getMaxHealth() : 0.0f);
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (var p : serverLevel.players()) {
                if (p.distanceToSqr(this) <= 64.0 * 64.0) {
                    bossBar.addPlayer(p);
                } else {
                    bossBar.removePlayer(p);
                }
            }
        }
    }

    /** Defeat resolution honors the authored mode contract (P3-2, issue #59). */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        // An already-dead entity never re-resolves — prevents a second
        // NpcDefeatedEvent during the corpse window (kill() reaches die()
        // directly, bypassing hurt()'s isDeadOrDying check).
        if (this.dead) {
            return;
        }
        if (this.deathDropsResolved) {
            // Reloaded corpse-husk: the defeat contract already resolved and
            // authored drops already rolled before unload — run only the
            // vanilla corpse path so the body cleans up normally.
            super.die(source);
            return;
        }
        // A resolved defeat never re-resolves: bypass-invulnerability sources
        // (void, /kill) hitting a hidden statue must not reset the respawn
        // countdown or republish the defeat event. The forced health is
        // restored because vanilla tickDeath() (driven by tick(), not our
        // frozen aiStep) would corpse-remove a hidden statue left at 0 HP.
        if (hiddenDefeatTicksLeft != 0) {
            this.setHealth(Math.min(1.0f, this.getMaxHealth()));
            return;
        }
        if (!this.level().isClientSide && !this.isRemoved()) {
            // P9-2: killed hook fires before the defeat-mode resolution so a
            // scripted reaction observes the kill regardless of outcome mode.
            dispatchScriptHook(com.storynpcs.script.ScriptHook.KILLED,
                    source.getEntity() instanceof ServerPlayer sp ? sp : null);
            var mod = StoryNpcsAccess.mod(this);
            if (mod != null && mod.getRegistry() != null) {
                // Resolve unconditionally — authored defeat semantics are not
                // coupled to the event bus being reachable.
                var statsOpt = state.getStats(mod.getRegistry());
                if (statsOpt.isPresent()) {
                    var stats = statsOpt.get();
                    var decision = com.storynpcs.domain.npc.DefeatResolution.resolve(stats);
                    // DIE (and unresolved stats) take the normal death path;
                    // HIDE/FLEE suppress it — no corpse, drops, XP, or removal.
                    // The event publishes AFTER the mode dispatch so
                    // subscribers observe post-dispatch state (a HIDE
                    // subscriber sees isHiddenDefeat()==true, a FLEE
                    // subscriber sees the threshold-restored health).
                    if (!decision.performsDeath()) {
                        switch (decision.mode()) {
                            case HIDE -> enterHiddenDefeat(decision.hiddenTicks());
                            case FLEE -> fleeDefeat(decision, source);
                            default -> { }
                        }
                        publishDefeatedEvent(mod, stats);
                        return;
                    }
                    publishDefeatedEvent(mod, stats);
                    // B7 "transform" vocabulary: on a lethal defeat the
                    // projection rebinds to the authored definition at full
                    // health instead of leaving a corpse. Only resolvable
                    // targets transform — an unbound transform id falls back
                    // to the authored death path.
                    var transformId = state.resolveDefinition(mod.getRegistry())
                            .map(NpcDefinition::getAi)
                            .map(com.storynpcs.domain.npc.NpcAi::getDefeatTransformId)
                            .orElse(null);
                    if (transformId != null
                            && mod.getRegistry().getNpc(transformId).isPresent()) {
                        transformInto(transformId);
                        return;
                    }
                }
            }
        }
        super.die(source);
    }

    /**
     * B7 transform: rebind this projection to the new definition and restore
     * full health. The logical actor identity is kept — a transformed NPC is
     * still the same actor — but all authored projections re-apply. Health is
     * restored explicitly because disk-loaded entities never auto-heal on
     * re-application.
     */
    private void transformInto(NamespacedId definitionId) {
        this.deathDropsResolved = false;
        this.threatManager.clearAll();
        this.setTarget(null);
        this.setDefinitionId(definitionId.asString());
        this.setHealth(this.getMaxHealth());
    }

    private void publishDefeatedEvent(StoryNpcs mod,
                                      com.storynpcs.domain.npc.NpcStats stats) {
        if (mod.getEventPublisher() == null) {
            return;
        }
        NamespacedId definitionId = state.resolveDefinition(mod.getRegistry())
                .map(NpcDefinition::getId).orElse(null);
        var defeat = stats.getDefeat();
        mod.getEventPublisher().publish(new com.storynpcs.api.event.NpcDefeatedEvent(
                definitionId, this.getUUID(),
                defeat != null ? defeat.getMode() : com.storynpcs.domain.npc.NpcStats.Defeat.Mode.DIE,
                stats.getRespawnTimeSeconds(), stats.getXpReward()));
    }

    /**
     * Authored drop contract (P3-4, VERIFIED_TARGET_SOURCE): on a real death
     * each occupied drop entry rolls independently against its authored
     * chance, winning stacks spawn as world items at the NPC's eye position
     * (40-tick pickup delay, small random scatter), and the authored
     * {@code minExp}..{@code maxExp} range rolls into experience orbs.
     * {@code AUTO_PICKUP} delivers to the killer player's inventory instead
     * (leftovers stay in the world; orbs spawn at the killer); {@code NOTHING}
     * suppresses the table entirely. HIDE/FLEE never reach this — they skip
     * {@code super.die}. Equipment itself never drops: its vanilla drop
     * chances are pinned to 0 in {@link #applyEquipmentProjection}.
     */
    @Override
    protected void dropAllDeathLoot(net.minecraft.server.level.ServerLevel level,
                                    DamageSource source) {
        if (this.deathDropsResolved) {
            // A resolved corpse reloading mid-window never re-rolls.
            return;
        }
        this.deathDropsResolved = true;
        super.dropAllDeathLoot(level, source);
        var mod = StoryNpcsAccess.mod(this);
        var inventory = mod != null
                ? state.resolveDefinition(mod.getRegistry())
                        .map(NpcDefinition::getInventory).orElse(null)
                : null;
        if (inventory == null
                || inventory.getLootMode() == com.storynpcs.domain.npc.NpcInventory.LootMode.NOTHING) {
            return;
        }
        // Seeded off the world RNG — the roller itself stays headless and
        // fixed-seed fixtures exercise identical semantics in JUnit.
        var roll = com.storynpcs.domain.npc.NpcDropRoll.roll(inventory,
                new java.util.Random(this.getRandom().nextLong()));
        // VERIFIED_TARGET_SOURCE: the target resolves tameable/pet kills to
        // the owner (NoppesUtilServer.GetDamageSourcee) — a wolf kill is the
        // owner's kill for AUTO_PICKUP delivery.
        net.minecraft.world.entity.Entity killer = source.getEntity();
        if (killer instanceof net.minecraft.world.entity.OwnableEntity ownable
                && ownable.getOwner() != null) {
            killer = ownable.getOwner();
        }
        boolean autoPickup = inventory.getLootMode()
                == com.storynpcs.domain.npc.NpcInventory.LootMode.AUTO_PICKUP
                && killer instanceof Player;

        java.util.List<com.storynpcs.domain.npc.NpcItemStack> dropped =
                new java.util.ArrayList<>(roll.drops().size());
        for (var rolled : roll.drops()) {
            var stack = resolveDropStack(rolled.item());
            if (stack == null) continue;
            if (autoPickup && killer instanceof Player player) {
                // Faithful port of the target's AUTO_PICKUP branch: the item
                // entity exists in the world with a short pickup delay, the
                // killer's inventory absorbs what it can (mutating the entity's
                // stack to the remainder), take() credits that entity so the
                // pickup packet/statistics fire, and a fully-absorbed entity
                // discards — leftovers stay in the world.
                var entity = new net.minecraft.world.entity.item.ItemEntity(
                        level, this.getX(), this.getY() - 0.3F + this.getEyeHeight(),
                        this.getZ(), stack);
                entity.setPickUpDelay(2);
                level.addFreshEntity(entity);
                net.minecraft.world.item.Item pickedItem = stack.getItem();
                int before = stack.getCount();
                player.getInventory().add(stack);
                int absorbed = before - stack.getCount();
                if (absorbed > 0) {
                    player.take(entity, absorbed);
                    player.awardStat(net.minecraft.stats.Stats.ITEM_PICKED_UP.get(pickedItem), absorbed);
                    player.onItemPickup(entity);
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.2F,
                            ((this.getRandom().nextFloat() - this.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
                }
                if (stack.isEmpty()) {
                    entity.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                }
                dropped.add(com.storynpcs.domain.npc.NpcItemStack.of(
                        rolled.item().itemId(), before, rolled.item().components()));
                continue;
            }
            spawnWorldDrop(stack);
            dropped.add(com.storynpcs.domain.npc.NpcItemStack.of(
                    rolled.item().itemId(), stack.getCount(), rolled.item().components()));
        }
        int exp = roll.experience();
        while (exp > 0) {
            int value = net.minecraft.world.entity.ExperienceOrb.getExperienceValue(exp);
            exp -= value;
            var orbPos = autoPickup ? killer : this;
            level.addFreshEntity(new net.minecraft.world.entity.ExperienceOrb(
                    level, orbPos.getX(), orbPos.getY(), orbPos.getZ(), value));
        }
        if (mod != null && mod.getEventPublisher() != null
                && (!dropped.isEmpty() || roll.experience() > 0)) {
            NamespacedId definitionId = state.resolveDefinition(mod.getRegistry())
                    .map(NpcDefinition::getId).orElse(null);
            mod.getEventPublisher().publish(new com.storynpcs.api.event.NpcLootDroppedEvent(
                    definitionId, this.getUUID(),
                    killer != null ? killer.getUUID() : null,
                    java.util.List.copyOf(dropped), roll.experience()));
        }
    }

    /** Resolves an authored drop to a live {@code ItemStack}, or null when the item is unknown. */
    private net.minecraft.world.item.ItemStack resolveDropStack(
            com.storynpcs.domain.npc.NpcItemStack authored) {
        return resolveAuthoredStack(authored, "drop");
    }

    /** World-drop spawn — VERIFIED_TARGET_SOURCE geometry: eye-level, 40-tick delay, random scatter. */
    private void spawnWorldDrop(net.minecraft.world.item.ItemStack stack) {
        var entity = new net.minecraft.world.entity.item.ItemEntity(
                this.level(), this.getX(), this.getY() - 0.3F + this.getEyeHeight(), this.getZ(), stack);
        entity.setPickUpDelay(40);
        float spread = this.getRandom().nextFloat() * 0.5F;
        float angle = this.getRandom().nextFloat() * (float) Math.PI * 2.0F;
        entity.setDeltaMovement(
                -net.minecraft.util.Mth.sin(angle) * spread, 0.2F,
                net.minecraft.util.Mth.cos(angle) * spread);
        this.level().addFreshEntity(entity);
    }

    /**
     * /kill and Entity.kill() are removal intent, not damage — they discard a
     * defeat-resolved projection outright (a hidden statue or authored-FLEE
     * NPC would otherwise survive, still reporting "Killed" to the admin).
     * DIE-mode NPCs take the normal death path so corpse/drops resolve.
     */
    @Override
    public void kill() {
        if (hiddenDefeatTicksLeft != 0) {
            this.discard();
            return;
        }
        var mod = StoryNpcsAccess.mod(this);
        boolean survives = !this.level().isClientSide && mod != null && mod.getRegistry() != null
                && state.getStats(mod.getRegistry())
                        .map(stats -> !com.storynpcs.domain.npc.DefeatResolution.resolve(stats).performsDeath())
                        .orElse(false);
        if (survives) {
            this.discard();
            return;
        }
        super.kill();
    }

    /**
     * HIDE defeat: the projection becomes an invisible, invulnerable,
     * non-physical statue and reappears at its start position once the
     * authored respawn timer elapses. {@code ticks < 0} stays hidden until
     * removal (authored respawn time <= 0).
     */
    private void enterHiddenDefeat(int ticks) {
        this.hiddenDefeatTicksLeft = ticks;
        this.setHealth(Math.min(1.0f, this.getMaxHealth()));
        this.threatManager.clearAll();
        this.setTarget(null);
        this.getNavigation().stop();
        // Clear transient pose/use state so the statue never reappears
        // mid-action (e.g. still sleeping or drawing a bow).
        this.setPose(net.minecraft.world.entity.Pose.STANDING);
        this.stopUsingItem();
        this.stopRiding();
        this.ejectPassengers();
        this.setInvisible(true);
        // Glowing outline renders on invisible entities — suppress it while
        // hidden and restore the authored flag on reappearance.
        this.setGlowingTag(false);
        // Custom nameplates render on invisible entities too — including the
        // crosshair-pick plate (shouldShowName checks hasCustomName). Clearing
        // the synced name suppresses it; reappear re-applies the authored one.
        this.setCustomNameVisible(false);
        this.setCustomName(null);
        this.wasInvulnerableBeforeHide = this.isInvulnerable();
        this.wasNoPhysicsBeforeHide = this.noPhysics;
        this.setInvulnerable(true);
        this.noPhysics = true;
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar = null;
        }
    }

    /**
     * FLEE defeat: survive at the authored threshold, disengage, return home.
     * Bypass-invulnerability sources (void, /kill) teleport home directly —
     * pathfinding cannot resolve a position below the world floor, so
     * navigation would leave the NPC in an endless void-flee treadmill.
     */
    private void fleeDefeat(com.storynpcs.domain.npc.DefeatResolution.Decision decision,
                            net.minecraft.world.damagesource.DamageSource source) {
        this.setHealth(Math.max(1.0f, this.getMaxHealth() * decision.healthAfterFraction()));
        this.threatManager.clearAll();
        this.setTarget(null);
        this.getNavigation().stop();
        if (decision.returnsHome()) {
            net.minecraft.core.BlockPos home = getStartPosition();
            this.fallDistance = 0.0F; // banked fall damage must not kill on arrival
            if (home == null || home.getY() < this.level().dimensionType().minY()) {
                // A lazy-snapshotted or corrupted home below the world floor
                // can never resolve — flee in place instead.
                return;
            }
            if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                this.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            } else {
                this.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.2);
            }
        }
    }

    /**
     * Ends the hidden window: teleport home, restore to full health, and
     * re-activate physics/visibility. Emits {@link com.storynpcs.api.event.NpcRespawnedEvent}.
     */
    private void reappearFromHiddenDefeat() {
        net.minecraft.core.BlockPos home = getStartPosition();
        // A home below the world floor (lazy snapshot, corrupt NBT) resolves
        // in place — never teleport into the void.
        if (home != null && home.getY() >= this.level().dimensionType().minY()) {
            this.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
        }
        this.fallDistance = 0.0F;
        this.setHealth(this.getMaxHealth());
        // Restore the *authored* visibility flag — an authored visibility=1
        // NPC must stay invisible after the hide/respawn cycle.
        boolean authoredInvisible = getDefinition()
                .map(d -> d.getDisplay() != null && d.getDisplay().getVisibility() == 1)
                .orElse(false);
        this.setInvisible(authoredInvisible);
        this.setGlowingTag(getDefinition()
                .map(d -> d.getDisplay() != null && d.getDisplay().isOverlayGlowing())
                .orElse(false));
        this.setInvulnerable(wasInvulnerableBeforeHide);
        this.noPhysics = wasNoPhysicsBeforeHide;
        // Re-apply the authored nameplate the same way applyDefinition does.
        var modForName = StoryNpcsAccess.mod(this);
        if (modForName != null) {
            state.getDisplayName(modForName.getRegistry()).ifPresent(name -> {
                this.setCustomName(net.minecraft.network.chat.Component.literal(name));
                this.setCustomNameVisible(true);
            });
        }
        // Nothing seeded during the frozen window may survive reappearance —
        // event-bus threat writes (witness scans, shout alerts) are suppressed
        // while hidden, and this clears any path that slipped through.
        this.threatManager.clearAll();
        this.setTarget(null);
        getDefinition().map(d -> d.getAi())
                .ifPresent(ai -> applyAnimationStance(ai.getAnimationStance()));
        var mod = StoryNpcsAccess.mod(this);
        if (mod != null && mod.getEventPublisher() != null) {
            NamespacedId definitionId = state.resolveDefinition(mod.getRegistry())
                    .map(NpcDefinition::getId).orElse(null);
            mod.getEventPublisher().publish(new com.storynpcs.api.event.NpcRespawnedEvent(
                    definitionId, this.getUUID()));
        }
    }

    /**
     * A HIDE-resolved statue is not a valid hostile target and must not
     * change dimensions (a portal transfer mid-countdown would restore it at
     * start coordinates in the wrong dimension).
     */
    /**
     * Hidden-defeat invisibility is posture, not effect-derived. Vanilla
     * recomputes the flag from {@code activeEffects} whenever
     * {@code effectsDirty} fires — any effect add/update/remove on a statue
     * clears it, resurfacing the NPC mid-countdown. Re-pin it here.
     */
    @Override
    protected void updateInvisibilityStatus() {
        if (isHiddenDefeat()) {
            this.setInvisible(true);
            return;
        }
        super.updateInvisibilityStatus();
    }

    public boolean isHiddenDefeat() {
        return hiddenDefeatTicksLeft != 0;
    }

    /**
     * A hidden-defeat statue must not dimension-transfer mid-countdown — the
     * restored entity would reappear at start coordinates in the wrong
     * dimension.
     */
    @Override
    public boolean canChangeDimensions(net.minecraft.world.level.Level oldLevel,
                                       net.minecraft.world.level.Level newLevel) {
        return hiddenDefeatTicksLeft == 0 && super.canChangeDimensions(oldLevel, newLevel);
    }

    public FollowerRole getFollowerRole() {
        return followerRole;
    }

    public void setFollowerRole(FollowerRole followerRole) {
        this.followerRole = followerRole;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // A hidden-defeat statue is not interactable — FAIL (not PASS) so the
        // item-use fallback (name tags, leads) cannot reach it either. This
        // check precedes the hand filter so off-hand items are blocked too.
        if (hiddenDefeatTicksLeft != 0) {
            return InteractionResult.FAIL;
        }

        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        // Creator tools inspect rather than interact — PASS so the fall-through
        // to Item#interactLivingEntity reaches NbtBookItem (#148). Without
        // this, every SUCCESS path below would consume the interact first and
        // the book could never open on a StoryNPC.
        if (player.getItemInHand(hand).getItem() instanceof com.storynpcs.item.NbtBookItem) {
            return InteractionResult.PASS;
        }

        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Prevent interacting while dead, spectating, or through solid obstacles without line of sight (VULN-12)
        if (!player.isAlive() || player.isSpectator() || !this.hasLineOfSight(player)) {
            return InteractionResult.FAIL;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            var mod = StoryNpcsAccess.mod(this);
            // P9-2: interact hook — budgeted, before the dialogue/follower flow.
            dispatchScriptHook(com.storynpcs.script.ScriptHook.INTERACT, serverPlayer);

            // Shift-right-click to cycle follower states if player is owner
            if (followerRole != null && followerRole.isOwnedBy(serverPlayer.getUUID()) && serverPlayer.isShiftKeyDown()) {
                FollowerRole.State nextState = switch (followerRole.getState()) {
                    case FOLLOWING -> FollowerRole.State.STAYING;
                    case STAYING -> FollowerRole.State.GUARDING;
                    case GUARDING -> FollowerRole.State.FOLLOWING;
                };

                if (mod != null) {
                    NamespacedId npcId = null;
                    try {
                        npcId = NamespacedId.of(getDefinitionId());
                    } catch (Exception ignored) {}
                    if (npcId != null) {
                        mod.getApplicationService().mutateFollowerState(
                                com.storynpcs.service.FollowerStateMutationRequest.setState(
                                        "player", serverPlayer.getUUID(), serverPlayer.getUUID(),
                                        npcId, nextState, java.util.UUID.randomUUID()),
                                followerRole);
                    } else {
                        followerRole.setState(nextState);
                    }
                } else {
                    followerRole.setState(nextState);
                }

                String npcName = this.getName().getString();
                String stateMsg = (nextState == FollowerRole.State.FOLLOWING)
                        ? "§6" + npcName + "§r is now §aFOLLOWING§r (§e" + followerRole.getFormation().name() + "§r formation)."
                        : "§6" + npcName + "§r is now §e" + nextState.name() + "§r.";
                serverPlayer.sendSystemMessage(Component.literal(stateMsg), true);
                return InteractionResult.SUCCESS;
            }

            if (mod != null) {
                var viewOpt = state.interact(serverPlayer.getUUID(), mod.getApplicationService(), mod.getRegistry(),
                        this.getUUID(), this.level().dimension().location().toString(), this.getX(), this.getY(), this.getZ());
                if (viewOpt.isPresent()) {
                    // P9-2: dialogue opening on a scripted actor is a DIALOG
                    // hook dispatch — the bound script observes the event
                    // through the budgeted scheduler.
                    dispatchScriptHook(com.storynpcs.script.ScriptHook.DIALOG);
                    StoryNpcsNetwork.sendOpenDialogue(serverPlayer, viewOpt.get());
                    return InteractionResult.SUCCESS;
                } else if (!serverPlayer.isShiftKeyDown()) {
                    // No dialogue — surface configured roles instead of "nothing to say"
                    com.storynpcs.domain.npc.NpcDefinition roleDef = null;
                    try {
                        var roleNpcId = NamespacedId.of(getDefinitionId());
                        roleDef = mod.getRegistry().getNpc(roleNpcId).orElse(null);
                    } catch (Exception ignored) {}
                    // P9-2: any interaction with a scripted actor dispatches INTERACT.
                    dispatchScriptHook(com.storynpcs.script.ScriptHook.INTERACT);
                    if (roleDef != null && roleDef.getTrader() != null) {
                        StoryNpcsNetwork.sendTradeOpen(serverPlayer, roleDef);
                        return InteractionResult.SUCCESS;
                    }
                    if (roleDef != null && roleDef.getBanker() != null) {
                        StoryNpcsNetwork.sendBankOpen(serverPlayer, roleDef);
                        return InteractionResult.SUCCESS;
                    }
                    // P6-4 ITEM_GIVER job: bounded per-player cooldown + configured item.
                    if (jobInstance != null
                            && jobInstance.getConfig().getType() == com.storynpcs.domain.job.JobType.ITEM_GIVER
                            && roleDef != null && roleDef.getJob() != null
                            && roleDef.getJob().getItemId() != null) {
                        long now = this.level().getGameTime();
                        Long last = itemGiverCooldowns.get(serverPlayer.getUUID());
                        if (last == null || now - last >= roleDef.getJob().getInteractionCooldownTicks()) {
                            var itemRl = net.minecraft.resources.ResourceLocation.tryParse(
                                    roleDef.getJob().getItemId().toString());
                            var item = itemRl != null ? net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getOptional(itemRl).orElse(null) : null;
                            if (item != null) {
                                itemGiverCooldowns.put(serverPlayer.getUUID(), now);
                                var stack = new net.minecraft.world.item.ItemStack(
                                        item, Math.max(1, roleDef.getJob().getItemCount()));
                                if (!serverPlayer.getInventory().add(stack)) {
                                    serverPlayer.drop(stack, false);
                                }
                                serverPlayer.sendSystemMessage(Component.literal(
                                        "§6" + this.getName().getString() + "§r gives you "
                                                + stack.getCount() + "× " + itemRl.getPath() + "."), true);
                            }
                        } else {
                            serverPlayer.sendSystemMessage(Component.literal(
                                    "§7[" + this.getName().getString() + "] §f*Has nothing more to give right now.*"), true);
                        }
                        return InteractionResult.SUCCESS;
                    }
                    // P6-2 postman: delivers pending quest mail within range.
                    if (roleDef != null && roleDef.getPostman() != null) {
                        double range = roleDef.getPostman().getDeliveryRangeBlocks();
                        if (serverPlayer.distanceToSqr(this) > range * range) {
                            return InteractionResult.SUCCESS;
                        }
                        var mail = mod.getApplicationService() != null
                                ? mod.getApplicationService().deliverQuestMail(
                                        serverPlayer.getUUID(), roleDef.getPostman().getMailboxCapacity())
                                : java.util.Optional.<com.storynpcs.service.StoryNpcsApplicationService
                                        .MailDeliverySummary>empty();
                        if (mail.isPresent()) {
                            var summary = mail.get();
                            serverPlayer.sendSystemMessage(Component.literal(
                                    "§6" + this.getName().getString() + "§r delivers §e"
                                            + summary.mailsClaimed() + "§r mail(s):"));
                            for (String line : summary.itemLines()) {
                                serverPlayer.sendSystemMessage(Component.literal("  §7- " + line));
                            }
                            if (summary.experienceGranted() > 0) {
                                serverPlayer.sendSystemMessage(Component.literal(
                                        "  §a+" + summary.experienceGranted() + " experience"));
                            }
                        } else {
                            serverPlayer.sendSystemMessage(Component.literal(
                                    "§7[" + this.getName().getString() + "] §f*No mail for you today.*"), true);
                        }
                        return InteractionResult.SUCCESS;
                    }
                    if (serverPlayer.hasPermissions(2)) {
                        serverPlayer.sendSystemMessage(Component.literal("§e[StoryNPCs] NPC '" + this.getName().getString() + "' (" + getDefinitionId() + ") has no dialogue configured or loaded."));
                    } else {
                        serverPlayer.sendSystemMessage(Component.literal("§7[" + this.getName().getString() + "] §f*Has nothing to say.*"), true);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
        }

        return super.mobInteract(player, hand);
    }

    /**
     * P9-2: dispatch a script hook for the bound CONVERSATION/PUPPET script
     * through the budgeted scheduler. The script body emotes the script id —
     * failures count toward the scheduler's consecutive-failure quarantine.
     */
    public void dispatchScriptHook(com.storynpcs.script.ScriptHook hook) {
        var mod = com.storynpcs.StoryNpcsAccess.mod(this);
        var scheduler = mod != null ? mod.getScriptScheduler() : null;
        if (scheduler == null || conversationScriptId == null || this.level().isClientSide) {
            return;
        }
        scheduler.dispatch(conversationScriptId, hook, this::runConversationScriptBody);
    }

    /** Built-in script body for conversation/puppet jobs: a bounded emote line. */
    private void runConversationScriptBody() {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        String line = getName().getString() + " performs "
                + (jobInstance != null && jobInstance.getConfig().getScriptId() != null
                ? jobInstance.getConfig().getScriptId().getPath() : "a conversation");
        for (var p : serverLevel.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                this.getBoundingBox().inflate(16.0))) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7" + line), true);
        }
    }

    /** CHUNK_LOADER job bookkeeping — forced chunks must never outlive the actor. */
    public java.util.Set<net.minecraft.world.level.ChunkPos> jobForcedChunks() {
        return jobForcedChunks;
    }

    /**
     * P6-4 job lifecycle event: publishes the observed {@link
     * com.storynpcs.domain.job.JobInstance.State} transition (null previous =
     * bind). The contract is the instance lifecycle, not config identity — a
     * re-applied definition produces a STOPPED + RUNNING pair. No-ops
     * client-side or when the observed state did not actually change.
     */
    private void publishJobTransition(com.storynpcs.domain.job.JobInstance job,
                                      com.storynpcs.domain.job.JobInstance.State previous) {
        if (job == null || previous == job.getState() || this.level().isClientSide) {
            return;
        }
        var mod = StoryNpcsAccess.mod(this);
        if (mod == null || mod.getEventPublisher() == null) {
            return;
        }
        var definitionId = state.resolveDefinition(mod.getRegistry())
                .map(NpcDefinition::getId).orElse(null);
        mod.getEventPublisher().publish(new com.storynpcs.api.event.NpcJobLifecycleEvent(
                definitionId, this.getUUID(), job.getConfig().getType(),
                previous, job.getState()));
    }

    private void releaseJobForcedChunks() {
        if (jobForcedChunks.isEmpty()
                || !(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            jobForcedChunks.clear();
            return;
        }
        for (var pos : jobForcedChunks) {
            // Shared chunks stay forced while another loader still claims them.
            com.storynpcs.runtime.job.NpcJobRuntime.releaseForcedChunk(this, serverLevel, pos);
        }
        jobForcedChunks.clear();
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (super.isInvulnerableTo(source)) {
            return true;
        }
        if (source.getEntity() instanceof Player player && player.isCreative()) {
            return false;
        }
        // VULN-52: OUT_OF_WORLD (void) must NEVER grant invulnerability; otherwise a PASSIVE NPC
        // falling into the void runs the hurt tick forever, causing a CPU-saturating loop.
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        var defOpt = getDefinition();
        // Unloaded / missing definition must NOT default to invulnerable ghost entity (VULN-15)
        if (defOpt.isEmpty()) {
            return false;
        }
        // P3-2: authored immunity toggles — per-source vetoes that apply
        // regardless of stance. "Sunlight" maps to DRY_OUT — the vanilla
        // sun-exposure damage source; daylight fire is covered by fire.
        var authoredImmunities = defOpt.get().getStats() != null
                ? defOpt.get().getStats().getImmunities() : null;
        if (authoredImmunities != null) {
            if (authoredImmunities.isFall()
                    && source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
                return true;
            }
            if (authoredImmunities.isFire()
                    && source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
                return true;
            }
            if (authoredImmunities.isDrowning()
                    && source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)) {
                return true;
            }
            if (authoredImmunities.isSunlight()
                    && source.is(net.minecraft.world.damagesource.DamageTypes.DRY_OUT)) {
                return true;
            }
            if (authoredImmunities.isPotion()
                    && (source.is(net.minecraft.world.damagesource.DamageTypes.MAGIC)
                    || source.is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC))) {
                return true;
            }
        }
        var mod = StoryNpcsAccess.mod(this);
        // VULN-55: read effective stance from per-entity override first
        TacticalStance stance = (mod != null)
                ? state.getEffectiveTacticalStance(mod.getRegistry())
                : (defOpt.get().getAi() != null ? defOpt.get().getAi().getTacticalStance() : null);
        if (stance != null && stance != TacticalStance.PASSIVE) {
            return false; // non-PASSIVE stance NPCs take normal damage
        }
        return true; // PASSIVE stance is invulnerable to non-bypassing damage
    }

    @Override
    public boolean canBeLeashed() {
        // VULN-50: Prevent any player from leashing StoryNPCs — all leash requests are rejected
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        // P9-2: damaged hook — budgeted; runs before threat/ability outcomes so
        // a scripted reaction observes the pre-mitigation hit.
        if (!this.level().isClientSide) {
            dispatchScriptHook(com.storynpcs.script.ScriptHook.DAMAGED,
                    source.getEntity() instanceof ServerPlayer sp ? sp : null);
        }
        // A hidden statue absorbs hits but never provokes — no threat writes.
        if (!this.level().isClientSide && hiddenDefeatTicksLeft == 0
                && source.getEntity() instanceof LivingEntity attacker) {
            // Prevent self-targeting loop (VULN-19)
            if (attacker != this && !attacker.getUUID().equals(this.getUUID())) {
                boolean sameFaction = false;
                var mod = StoryNpcsAccess.mod(this);
                if (attacker instanceof StoryNpcEntity otherNpc && mod != null) {
                    var myFaction = this.getState().getFactionId(mod.getRegistry());
                    var otherFaction = otherNpc.getState().getFactionId(mod.getRegistry());
                    if (myFaction.isPresent() && otherFaction.isPresent() && myFaction.get().equals(otherFaction.get())) {
                        sameFaction = true; // Friendly fire between same faction NPCs
                    }
                }
                // Only evaluate non-player hits here; player hits are evaluated with tolerance by WitnessProtectionManager (VULN-17)
                if (!sameFaction && !(attacker instanceof ServerPlayer)) {
                    this.threatManager.evaluateHit(attacker.getUUID(), this.level().getGameTime(), 0, 60);
                }

                // #147: DAMAGED-triggered abilities — BLOCK scales the hit,
                // pull/push/snare/teleport react to the attacker. Skip when
                // vanilla i-frames would reject the hit outright so cooldowns
                // are not burned on zero-damage invocations. The gate must
                // compare the resistance-scaled amount — the same value
                // super.hurt() will compare against lastHurt — or amplified
                // hits would deal differential damage without firing.
                float scaledAmount = scaleByAuthoredResistances(source, amount);
                if (this.invulnerableTime > this.invulnerableDuration / 2.0F
                        && scaledAmount <= this.lastHurt) {
                    return super.hurt(source, scaledAmount);
                }
                var abilityOutcomes = this.abilityController.onDamaged(
                        getDefinition().map(NpcDefinition::getAbilities).orElse(java.util.List.of()),
                        abilityEventInput(attacker, amount, this.level().getGameTime()));
                float damageMultiplier = 1.0F;
                for (var outcome : abilityOutcomes) {
                    if (outcome.kind() == com.storynpcs.ai.combat.NpcAbilityController.OutcomeKind.DAMAGE_MULTIPLIER) {
                        damageMultiplier *= (float) outcome.amount();
                    }
                }
                applyAbilityOutcomes(abilityOutcomes, attacker);
                return super.hurt(source, scaleByAuthoredResistances(source, amount * damageMultiplier));
            }
        }
        return super.hurt(source, scaleByAuthoredResistances(source, amount));
    }

    /**
     * Authored damage-resistance channels (P3-2, ADR-007): incoming damage is
     * scaled by the target-faithful {@code 2.0 - resistance} — {@code 0.0}
     * resistance is vulnerability (double damage), {@code 1.0} normal, and
     * {@code 2.0} immunity. Persisted out-of-range values pass through
     * unclamped (target NBT-read quirk): a value above {@code 2.0} produces a
     * negative scale and can heal on hit, matching the target. Scaling happens
     * after threat evaluation: a fully resisted hit still provokes.
     */
    private float scaleByAuthoredResistances(DamageSource source, float amount) {
        if (amount <= 0) {
            return amount;
        }
        var stats = getDefinition().map(d -> d.getStats()).orElse(null);
        var resistances = stats != null ? stats.getResistances() : null;
        if (resistances == null) {
            return amount;
        }
        double multiplier = resistances.damageScaleFor(
                source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE),
                source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION),
                source.getDirectEntity() instanceof LivingEntity);
        return (float) (amount * multiplier);
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effectInstance) {
        // P3-2: authored potion immunity rejects harmful effects entirely —
        // complementing the magic-damage veto in isInvulnerableTo.
        var stats = getDefinition().map(d -> d.getStats()).orElse(null);
        var immunities = stats != null ? stats.getImmunities() : null;
        if (immunities != null && immunities.isPotion()
                && effectInstance.getEffect().value().getCategory()
                        == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
            return false;
        }
        return super.canBeAffected(effectInstance);
    }

    @Override
    public void makeStuckInBlock(net.minecraft.world.level.block.state.BlockState state,
                                 net.minecraft.world.phys.Vec3 multiplier) {
        // P3-2: authored cobweb immunity — web blocks never impose the
        // slowdown multiplier; other makeStuckInBlock blocks behave normally.
        var stats = getDefinition().map(d -> d.getStats()).orElse(null);
        var immunities = stats != null ? stats.getImmunities() : null;
        if (immunities != null && immunities.isCobweb()
                && state.is(net.minecraft.world.level.block.Blocks.COBWEB)) {
            return;
        }
        super.makeStuckInBlock(state, multiplier);
    }

    @Override
    public void remove(RemovalReason reason) {
        // Path goals never outlive the entity: cancel navigation before the
        // lifecycle transition so no off-tick path mutation can leak past unload.
        this.getNavigation().stop();
        this.threatManager.clearAll();
        StoryNpcs mod = StoryNpcsAccess.mod(this);
        if (!this.level().isClientSide) {
            if (mod != null && conversationScriptId != null) {
                mod.getScriptScheduler().unregister(conversationScriptId);
                conversationScriptId = null;
            }
            // P6-4: jobs stop on actor unload/removal — policy decides pause vs stop.
            if (jobInstance != null) {
                var preUnload = jobInstance.getState();
                jobInstance.onActorUnload();
                publishJobTransition(jobInstance, preUnload);
            }
            releaseJobForcedChunks();
            // P4-2: drop any squad target claim so the slot is reclaimable.
            if (mod != null) {
                mod.releaseSquadAssignment(this.getUUID());
            }
        }
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar = null;
        }
        net.minecraft.server.MinecraftServer server = this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel.getServer()
                : null;
        String actorIdValue = getActorId();
        if (!this.level().isClientSide && mod != null && mod.getActorLifecycleService(server) != null
                && actorIdValue != null && !actorIdValue.isBlank()) {
            try {
                NamespacedId actorId = NamespacedId.of(actorIdValue);
                if (reason == RemovalReason.UNLOADED_TO_CHUNK) {
                    mod.getActorLifecycleService(server).unloadProjection(actorId, this.getUUID());
                } else {
                    mod.getActorLifecycleService(server).despawnProjection(actorId, this.getUUID());
                }
            } catch (RuntimeException e) {
                StoryNpcs.LOGGER.warn("Could not detach StoryNPC actor from projection {}: {}", this.getUUID(), e.getMessage());
            }
        }
        super.remove(reason);
        if (followerRole != null && followerRole.getOwnerUuid() != null) {
            if (mod != null) {
                mod.getFollowerGroup(server).unregister(followerRole.getOwnerUuid(), this.getUUID());
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        // ADR-007 ModRev-equivalent: the persisted data revision lets future
        // format changes distinguish pre-change saves during migration.
        EntityDataRevision.write(compound);
        compound.putString("StoryNpcDefinitionId", getDefinitionId());
        // Never persist a blank actor ID — an entity loaded before the actor
        // runtime existed would otherwise carry StoryNpcActorId:"" forever,
        // permanently skipping legacy reconciliation on every later load.
        if (getActorId() != null && !getActorId().isBlank()) {
            compound.putString("StoryNpcActorId", getActorId());
        }
        if (startPosition != null) {
            compound.putInt("StartX", startPosition.getX());
            compound.putInt("StartY", startPosition.getY());
            compound.putInt("StartZ", startPosition.getZ());
        }
        if (followerRole != null) {
            CompoundTag followerTag = new CompoundTag();
            if (followerRole.getOwnerUuid() != null) {
                followerTag.putUUID("Owner", followerRole.getOwnerUuid());
            }
            followerTag.putString("State", followerRole.getState().name());
            followerTag.putString("Formation", followerRole.getFormation().name());
            followerTag.putInt("Slot", followerRole.getFormationSlot());
            followerTag.putDouble("Spacing", followerRole.getFormationSpacing());
            followerTag.putInt("DaysHired", followerRole.getDaysHired());
            followerTag.putInt("DailyRate", followerRole.getDailyRate());
            compound.put("Follower", followerTag);
        }
        if (companionHiredTick >= 0 || companionWageLedger.getLastChargedPeriod() >= 0) {
            CompoundTag companionTag = new CompoundTag();
            companionTag.putLong("HiredTick", companionHiredTick);
            companionTag.putLong("LastChargedWagePeriod", companionWageLedger.getLastChargedPeriod());
            companionTag.putBoolean("Paused", companionPaused);
            compound.put("Companion", companionTag);
        }
        if (hiddenDefeatTicksLeft != 0) {
            compound.putInt("HiddenDefeatTicksLeft", hiddenDefeatTicksLeft);
            compound.putBoolean("HiddenDefeatWasInvulnerable", wasInvulnerableBeforeHide);
            compound.putBoolean("HiddenDefeatWasNoPhysics", wasNoPhysicsBeforeHide);
        }
        if (deathDropsResolved) {
            compound.putBoolean("StoryNpcDeathResolved", true);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        loadingSavedData = true;
        loadedFromDisk = true;
        // Establish durable logical identity before definition binding. This prevents
        // a replacement projection from first registering a UUID-derived orphan actor.
        try {
            this.loadedDataRevision = EntityDataRevision.read(compound);
            if (compound.contains("StoryNpcActorId")) {
                this.state.setActorId(compound.getString("StoryNpcActorId"));
            }
            // Hidden-defeat state must be restored BEFORE definition binding:
            // applyDefinition re-projects nameplate/glow/visibility and would
            // resurface a counting-down statue if it ran while hiddenTicks==0.
            if (compound.contains("HiddenDefeatTicksLeft")) {
                this.hiddenDefeatTicksLeft = compound.getInt("HiddenDefeatTicksLeft");
            }
            // Resolved-corpse flag: a husk reloading mid-corpse-window runs
            // die() for cleanup only — authored drops/events never re-fire.
            this.deathDropsResolved = compound.getBoolean("StoryNpcDeathResolved");
            if (compound.contains("StoryNpcDefinitionId")) {
                setDefinitionId(compound.getString("StoryNpcDefinitionId"));
            }
            if (compound.contains("StartX") && compound.contains("StartY") && compound.contains("StartZ")) {
                this.startPosition = new BlockPos(compound.getInt("StartX"), compound.getInt("StartY"), compound.getInt("StartZ"));
            }
            if (compound.contains("Follower")) {
                CompoundTag followerTag = compound.getCompound("Follower");
                this.followerRole = new FollowerRole();
                if (followerTag.hasUUID("Owner")) {
                    this.followerRole.setOwnerUuid(followerTag.getUUID("Owner"));
                }
                if (followerTag.contains("State")) {
                    try {
                        this.followerRole.setState(FollowerRole.State.valueOf(followerTag.getString("State")));
                    } catch (Exception ignored) {}
                }
                if (followerTag.contains("Formation")) {
                    this.followerRole.setFormation(FormationType.fromString(followerTag.getString("Formation")));
                }
                if (followerTag.contains("Slot")) {
                    this.followerRole.setFormationSlot(followerTag.getInt("Slot"));
                }
                if (followerTag.contains("Spacing")) {
                    this.followerRole.setFormationSpacing(followerTag.getDouble("Spacing"));
                }
                if (followerTag.contains("DaysHired")) {
                    this.followerRole.setDaysHired(followerTag.getInt("DaysHired"));
                }
                if (followerTag.contains("DailyRate")) {
                    this.followerRole.setDailyRate(followerTag.getInt("DailyRate"));
                }
            }
            if (compound.contains("Companion")) {
                CompoundTag companionTag = compound.getCompound("Companion");
                this.companionHiredTick = companionTag.getLong("HiredTick");
                this.companionWageLedger.setLastChargedPeriod(
                        companionTag.getLong("LastChargedWagePeriod"));
                this.companionPaused = companionTag.getBoolean("Paused");
            }
            if (this.hiddenDefeatTicksLeft != 0) {
                this.wasInvulnerableBeforeHide = compound.getBoolean("HiddenDefeatWasInvulnerable");
                this.wasNoPhysicsBeforeHide = compound.getBoolean("HiddenDefeatWasNoPhysics");
                // Restore the full hidden-defeat posture — the countdown
                // surviving a world save must restore the suppression too.
                this.setInvisible(true);
                this.setInvulnerable(true);
                this.noPhysics = true;
                this.setGlowingTag(false);
                this.setCustomNameVisible(false);
                this.setCustomName(null);
            }
        } finally {
            loadingSavedData = false;
        }

        reconcileActorBinding();
    }

    /**
     * Binds this entity's projection to its logical actor. Spawn-area and forced
     * chunks deserialize entities during level load — before ServerStartedEvent
     * registers the server-scoped actor runtime — so the world lifecycle handler
     * re-runs this reconciliation once the runtime exists. A blank actor ID is
     * treated as unbound (legacy reconciliation), never persisted, so a
     * pre-runtime load cannot poison future migrations with an empty
     * {@code StoryNpcActorId} NBT value.
     */
    public void reconcileActorBinding() {
        // Discarded entities must not bind a projection they no longer hold —
        // a stale PROJECTED record would block the actor's next real projection.
        if (this.level().isClientSide || this.isRemoved()) {
            return;
        }
        StoryNpcs mod = StoryNpcsAccess.mod(this);
        net.minecraft.server.MinecraftServer server = this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel.getServer()
                : null;
        if (mod == null || mod.getActorLifecycleService(server) == null) {
            return;
        }
        String actorId = getActorId();
        if (actorId == null || actorId.isBlank()) {
            try {
                NamespacedId definitionId = NamespacedId.of(getDefinitionId());
                var result = mod.getActorLifecycleService(server).bindLegacyProjection(definitionId, this.getUUID());
                if (result.applied() && result.actorId() != null) {
                    this.state.setActorId(result.actorId().toString());
                } else {
                    this.state.setActorId("");
                }
            } catch (RuntimeException e) {
                StoryNpcs.LOGGER.warn("Could not reconcile legacy StoryNPC projection {}: {}", this.getUUID(), e.getMessage());
            }
        } else {
            refreshActorProjection();
        }
    }
}
