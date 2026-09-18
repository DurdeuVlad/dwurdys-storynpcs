package com.storynpcs.entity;

import com.storynpcs.StoryNpcs;
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
import com.storynpcs.service.DialogueView;
import net.minecraft.world.damagesource.DamageSource;
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

    private final StoryNpcState state = new StoryNpcState();
    private final com.storynpcs.ai.combat.ThreatManager threatManager = new com.storynpcs.ai.combat.ThreatManager();
    private BlockPos startPosition;
    private FollowerRole followerRole;

    public StoryNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
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
        super.aiStep();
        if (!this.level().isClientSide && this.tickCount % 20 == 0) {
            this.threatManager.tick(5);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DEFINITION_ID, "");
    }

    public String getDefinitionId() {
        return this.entityData.get(DEFINITION_ID);
    }

    public void setDefinitionId(String definitionId) {
        this.entityData.set(DEFINITION_ID, definitionId != null ? definitionId : "");
        this.state.setDefinitionId(definitionId);
        applyDefinition();
    }

    public BlockPos getStartPosition() {
        if (startPosition == null) {
            startPosition = this.blockPosition();
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
        var mod = StoryNpcs.getInstance();
        return mod != null ? state.resolveDefinition(mod.getRegistry()) : Optional.empty();
    }

    public void applyDefinition() {
        var mod = StoryNpcs.getInstance();
        if (mod == null) return;

        state.getDisplayName(mod.getRegistry()).ifPresent(name -> {
            this.setCustomName(Component.literal(name));
            this.setCustomNameVisible(true);
        });

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
        });

        state.resolveDefinition(mod.getRegistry()).ifPresent(def -> {
            if (def.getAi() != null) {
                if (this.getNavigation() instanceof GroundPathNavigation groundNav) {
                    groundNav.setCanOpenDoors(def.getAi().isDoorInteract());
                }
                this.setPathfindingMalus(PathType.WATER, def.getAi().isAvoidWater() ? -1.0F : 0.0F);
            }
        });
    }

    public FollowerRole getFollowerRole() {
        return followerRole;
    }

    public void setFollowerRole(FollowerRole followerRole) {
        this.followerRole = followerRole;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
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
            var mod = StoryNpcs.getInstance();

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
                        mod.getApplicationService().setFollowerState(serverPlayer.getUUID(), npcId, followerRole, nextState);
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
                    StoryNpcsNetwork.sendOpenDialogue(serverPlayer, viewOpt.get());
                    return InteractionResult.SUCCESS;
                } else if (!serverPlayer.isShiftKeyDown()) {
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

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (super.isInvulnerableTo(source)) {
            return true;
        }
        if (source.getEntity() instanceof Player player && player.isCreative()) {
            return false;
        }
        var defOpt = getDefinition();
        // Unloaded / missing definition must NOT default to invulnerable ghost entity (VULN-15)
        if (defOpt.isEmpty()) {
            return false;
        }
        if (defOpt.get().getAi() != null) {
            var stance = defOpt.get().getAi().getTacticalStance();
            if (stance != null && stance != TacticalStance.PASSIVE) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (!this.level().isClientSide && source.getEntity() instanceof LivingEntity attacker) {
            // Prevent self-targeting loop (VULN-19)
            if (attacker != this && !attacker.getUUID().equals(this.getUUID())) {
                boolean sameFaction = false;
                var mod = StoryNpcs.getInstance();
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
        return super.hurt(source, amount);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (followerRole != null && followerRole.getOwnerUuid() != null) {
            FollowerGroup.unregister(followerRole.getOwnerUuid(), this.getUUID());
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putString("StoryNpcDefinitionId", getDefinitionId());
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
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
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
    }
}