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

    private final StoryNpcState state = new StoryNpcState();
    private final com.storynpcs.ai.combat.ThreatManager threatManager = new com.storynpcs.ai.combat.ThreatManager();
    private BlockPos startPosition;
    /**
     * HIDE-defeat state: {@code >0} counts down to reappearance,
     * {@code <0} means hidden indefinitely, {@code 0} means not hidden.
     */
    private int hiddenDefeatTicksLeft = 0;
    /** Whether the entity was already invulnerable before entering hidden defeat. */
    private boolean wasInvulnerableBeforeHide = false;
    /** Whether the entity already had noPhysics before entering hidden defeat. */
    private boolean wasNoPhysicsBeforeHide = false;
    private FollowerRole followerRole;
    private boolean loadingSavedData;

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
        this.goalSelector.addGoal(1, new com.storynpcs.ai.combat.NpcMeleeAttackGoal(this, 1.25D));
        this.goalSelector.addGoal(2, new NpcFollowFormationGoal(this, 1.0D, 1.35D, 1.75F, 24.0F));
        this.goalSelector.addGoal(3, new NpcPatrolGoal(this, 1.0D));
        this.goalSelector.addGoal(4, new NpcReturnToStartGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new NpcWanderingStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
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
            var defOpt = npc.getDefinition();
            if (defOpt.isEmpty()) return false;
            var ai = defOpt.get().getAi();
            if (ai == null || ai.getMovementType() != com.storynpcs.domain.npc.NpcAi.MovementType.WANDERING) {
                return false;
            }
            return super.canUse();
        }
    }

    public com.storynpcs.ai.combat.ThreatManager getThreatManager() {
        return threatManager;
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

        if (this.tickCount % 20 == 0) {
            if (sensingDue) {
                this.threatManager.tick(5);
            }
            if (animationDue) {
                updateBossBar();
            }
            tickCompanionWages(now);
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
            if (maxHealthAttr != null && stats.getMaxHealth() > 0) {
                maxHealthAttr.setBaseValue(stats.getMaxHealth());
                this.setHealth((float) stats.getMaxHealth());
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
            if (stats.getXpReward() > 0) {
                this.xpReward = stats.getXpReward();
            }
        });

        state.resolveDefinition(mod.getRegistry()).ifPresent(def -> {
            if (def.getAi() != null) {
                if (this.getNavigation() instanceof GroundPathNavigation groundNav) {
                    groundNav.setCanOpenDoors(def.getAi().isDoorInteract());
                }
                this.setPathfindingMalus(PathType.WATER, def.getAi().isAvoidWater() ? -1.0F : 0.0F);
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
                } catch (RuntimeException jobFailure) {
                    com.storynpcs.StoryNpcs.LOGGER.warn(
                            "NPC {} carries an invalid job config — job disabled: {}",
                            def.getId(), jobFailure.getMessage());
                }
            }
            if (previousJob != null && previousJob.getState()
                    != com.storynpcs.domain.job.JobInstance.State.STOPPED
                    && this.jobInstance == null) {
                previousJob.stop();
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
                }
            }
        }
        super.die(source);
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

        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Prevent interacting while dead, spectating, or through solid obstacles without line of sight (VULN-12)
        if (!player.isAlive() || player.isSpectator() || !this.hasLineOfSight(player)) {
            return InteractionResult.FAIL;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            var mod = StoryNpcsAccess.mod(this);

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
            }
        }
        return super.hurt(source, scaleByAuthoredResistances(source, amount));
    }

    /**
     * Authored damage-resistance channels (P3-2): incoming-damage multipliers
     * in [0, 2] — 1.0 normal, 0 fully resisted, above 1 amplified. Scaling
     * happens after threat evaluation: a fully resisted hit still provokes.
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
        double multiplier = 1.0;
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)) {
            multiplier = resistances.getArrow();
        } else if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
            multiplier = resistances.getExplosion();
        } else if (source.getDirectEntity() instanceof LivingEntity) {
            multiplier = resistances.getMelee();
        }
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
                jobInstance.onActorUnload();
            }
            releaseJobForcedChunks();
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
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        loadingSavedData = true;
        // Establish durable logical identity before definition binding. This prevents
        // a replacement projection from first registering a UUID-derived orphan actor.
        try {
            if (compound.contains("StoryNpcActorId")) {
                this.state.setActorId(compound.getString("StoryNpcActorId"));
            }
            // Hidden-defeat state must be restored BEFORE definition binding:
            // applyDefinition re-projects nameplate/glow/visibility and would
            // resurface a counting-down statue if it ran while hiddenTicks==0.
            if (compound.contains("HiddenDefeatTicksLeft")) {
                this.hiddenDefeatTicksLeft = compound.getInt("HiddenDefeatTicksLeft");
            }
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
