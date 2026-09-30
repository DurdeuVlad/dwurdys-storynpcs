package com.storynpcs.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import com.storynpcs.domain.npc.NpcStats;

/**
 * Server-authoritative projectile for the authored {@code stats.ranged}
 * contract (P3-2). Carries the authored area-damage radius, impact sound,
 * trail particle, on-hit effect, and projectile size — every field of the
 * schema is consumed here rather than silently ignored.
 */
public class NpcProjectileEntity extends AbstractArrow {

    private double areaDamageRadius;
    private String trailParticleId = "";
    private String impactSoundId = "";
    private String hitEffectId = "";
    private int hitEffectDurationTicks;
    private int hitEffectAmplifier;
    private float authoredSize = 0.5F;

    public NpcProjectileEntity(EntityType<? extends NpcProjectileEntity> type, Level level) {
        super(type, level);
        this.pickup = Pickup.DISALLOWED;
    }

    /** Applies the authored ranged contract; call before {@link #shoot}. */
    public void configure(NpcStats.Ranged ranged, LivingEntity owner) {
        setOwner(owner);
        setBaseDamage(ranged.getDamage());
        this.areaDamageRadius = ranged.getAreaDamage();
        this.trailParticleId = ranged.hasTrail() ? ranged.getTrailParticleId() : "";
        this.impactSoundId = ranged.getImpactSoundId();
        this.hitEffectId = ranged.getEffectId();
        this.hitEffectDurationTicks = ranged.getEffectDurationTicks();
        this.hitEffectAmplifier = ranged.getEffectAmplifier();
        this.authoredSize = (float) ranged.getProjectileSize();
        refreshDimensions();
        if (!ranged.getImpactSoundId().isBlank()) {
            ResourceLocation soundRl = ResourceLocation.tryParse(ranged.getImpactSoundId());
            if (soundRl != null) {
                BuiltInRegistries.SOUND_EVENT.getOptional(soundRl)
                        .ifPresent(this::setSoundEvent);
            }
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // Authored projectile size is per-instance — the entity type keeps the
        // default bounds, the authored value overrides them here.
        return authoredSize > 0.0F
                ? EntityDimensions.scalable(authoredSize, authoredSize)
                : super.getDimensions(pose);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        // NPC projectiles are never pickable — Pickup.DISALLOWED is set in the
        // constructor and no item form exists for the authored contract.
        return ItemStack.EMPTY;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel
                && this.trailParticleId != null && !this.trailParticleId.isBlank()) {
            ResourceLocation rl = ResourceLocation.tryParse(this.trailParticleId);
            var particle = rl != null ? BuiltInRegistries.PARTICLE_TYPE.getOptional(rl).orElse(null) : null;
            if (particle instanceof ParticleOptions options) {
                serverLevel.sendParticles(options, this.getX(), this.getY(), this.getZ(),
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (this.level().isClientSide) {
            return;
        }
        playAuthoredImpactSound();
        applyAuthoredAreaDamage(hitResult.getEntity());
    }

    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);
        // The authored sound was installed via setSoundEvent — super already
        // played it as the hit-ground sound; no manual replay here.
        if (this.level().isClientSide) {
            return;
        }
        applyAuthoredAreaDamage(null);
    }

    @Override
    protected void doPostHurtEffects(LivingEntity living) {
        super.doPostHurtEffects(living);
        if (hitEffectId.isBlank() || hitEffectDurationTicks <= 0) {
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(hitEffectId);
        if (rl == null) {
            return;
        }
        BuiltInRegistries.MOB_EFFECT.getHolder(rl).ifPresent(holder ->
                living.addEffect(new MobEffectInstance(holder,
                        hitEffectDurationTicks, hitEffectAmplifier), getOwner()));
    }

    /** Authored splash damage — hurt every living entity inside the radius. */
    private void applyAuthoredAreaDamage(net.minecraft.world.entity.Entity directHit) {
        if (areaDamageRadius <= 0.0 || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        DamageSource source = this.damageSources().arrow(this, getOwner());
        for (var victim : serverLevel.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(areaDamageRadius))) {
            if (victim == directHit || victim == getOwner() || !victim.isAlive()) {
                continue; // the direct target already took the arrow's own damage
            }
            victim.hurt(source, (float) this.getBaseDamage());
        }
    }

    private void playAuthoredImpactSound() {
        if (impactSoundId.isBlank()) {
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(impactSoundId);
        if (rl == null) {
            return;
        }
        BuiltInRegistries.SOUND_EVENT.getOptional(rl).ifPresent(sound ->
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        sound, SoundSource.HOSTILE, 1.0F, 1.0F));
    }
}
