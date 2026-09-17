package com.storynpcs.entity;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.network.StoryNpcsNetwork;
import com.storynpcs.service.DialogueView;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Optional;

public class StoryNpcEntity extends PathfinderMob {

    private static final EntityDataAccessor<String> DEFINITION_ID =
            SynchedEntityData.defineId(StoryNpcEntity.class, EntityDataSerializers.STRING);

    private final StoryNpcState state = new StoryNpcState();

    public StoryNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
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
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            var mod = StoryNpcs.getInstance();
            if (mod != null) {
                var viewOpt = state.interact(serverPlayer.getUUID(), mod.getApplicationService(), mod.getRegistry());
                if (viewOpt.isPresent()) {
                    StoryNpcsNetwork.sendOpenDialogue(serverPlayer, viewOpt.get());
                    return InteractionResult.SUCCESS;
                }
            }
        }

        return super.mobInteract(player, hand);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putString("StoryNpcDefinitionId", getDefinitionId());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("StoryNpcDefinitionId")) {
            setDefinitionId(compound.getString("StoryNpcDefinitionId"));
        }
    }
}