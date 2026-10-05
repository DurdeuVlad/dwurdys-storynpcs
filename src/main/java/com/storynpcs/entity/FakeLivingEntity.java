package com.storynpcs.entity;

import com.storynpcs.domain.support.SupportEntityRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * Owner-bound display puppet (issue #148, target {@code EntityFakeLiving}): a
 * LivingEntity that renders as another registered entity type for scripted
 * display scenes.
 *
 * <p>Lifecycle: persisted (display type + owner survive reload), but an
 * owner-bound puppet re-checks owner presence every
 * {@link SupportEntityRules#OWNER_RECHECK_PERIOD_TICKS} ticks and discards
 * when the owner is gone — the puppet never outlives its owner session.
 * Ownerless puppets persist as ordinary props.
 */
public class FakeLivingEntity extends LivingEntity {

    private static final EntityDataAccessor<String> DISPLAY_TYPE =
            SynchedEntityData.defineId(FakeLivingEntity.class, EntityDataSerializers.STRING);

    /** Creator/owner binding; {@code null} means an unbound prop. */
    private UUID ownerUuid;

    public FakeLivingEntity(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
        // A display puppet: no AI, no sounds, no damage. Despawn rules live
        // on Mob — a LivingEntity persists unless explicitly discarded.
        this.setSilent(true);
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D);
    }

    @Override
    public net.minecraft.world.entity.HumanoidArm getMainArm() {
        return net.minecraft.world.entity.HumanoidArm.RIGHT;
    }

    @Override
    public Iterable<net.minecraft.world.item.ItemStack> getHandSlots() {
        return java.util.List.of();
    }

    @Override
    public Iterable<net.minecraft.world.item.ItemStack> getArmorSlots() {
        return java.util.List.of();
    }

    @Override
    public net.minecraft.world.item.ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot,
                            net.minecraft.world.item.ItemStack stack) {
        // Display puppets carry no equipment.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAffectedByFluids() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DISPLAY_TYPE, "");
    }

    /** Entity type this puppet renders as; empty when none was assigned. */
    public Optional<EntityType<?>> displayEntityType() {
        String raw = this.entityData.get(DISPLAY_TYPE);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        return id == null ? Optional.empty()
                : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
    }

    public void setDisplayEntityType(EntityType<?> displayType) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(displayType);
        this.entityData.set(DISPLAY_TYPE, key.toString());
    }

    public Optional<UUID> getOwnerUuid() {
        return Optional.ofNullable(ownerUuid);
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide
                && this.tickCount % SupportEntityRules.OWNER_RECHECK_PERIOD_TICKS == 0
                && this.level() instanceof ServerLevel serverLevel) {
            // "Owner left" means left the server — an owner who changed
            // dimensions is still online and the puppet must not discard.
            boolean ownerPresent = ownerUuid != null
                    && serverLevel.getServer().getPlayerList().getPlayer(ownerUuid) != null;
            if (SupportEntityRules.fakeLivingShouldDiscard(ownerUuid != null, ownerPresent)) {
                discard();
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        String display = this.entityData.get(DISPLAY_TYPE);
        if (display != null && !display.isEmpty()) {
            tag.putString("FakeDisplayType", display);
        }
        if (ownerUuid != null) {
            tag.putUUID("FakeOwner", ownerUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("FakeDisplayType")) {
            this.entityData.set(DISPLAY_TYPE, tag.getString("FakeDisplayType"));
        }
        this.ownerUuid = tag.hasUUID("FakeOwner") ? tag.getUUID("FakeOwner") : null;
    }
}
