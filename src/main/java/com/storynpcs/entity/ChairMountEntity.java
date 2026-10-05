package com.storynpcs.entity;

import com.storynpcs.domain.support.SupportEntityRules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Transient seat entity (issue #148, target {@code EntityChairMount}): an
 * invisible, non-colliding mount point so players or NPCs can "sit" on a
 * clicked block.
 *
 * <p>Lifecycle is deliberately thin: the entity is {@code noSave} (transient —
 * chunk unload discards it) and self-discards once nothing has ridden it for
 * longer than {@link SupportEntityRules#CHAIR_SPAWN_GRACE_TICKS}. There is no
 * persistent state to leak across sessions.
 */
public class ChairMountEntity extends Entity {

    public ChairMountEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvisible(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide
                && SupportEntityRules.chairShouldDiscard(getPassengers().size(), this.tickCount)) {
            discard();
        }
    }
}
