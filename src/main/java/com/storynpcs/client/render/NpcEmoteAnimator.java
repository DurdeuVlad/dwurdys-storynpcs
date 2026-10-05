package com.storynpcs.client.render;

import com.storynpcs.domain.npc.NpcEmote;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Clean-room emote pose overrides for humanoid variants (issue #58), applied
 * after vanilla + stance setup inside {@code setupAnim}. Mirrors the target's
 * ten {@code Ani*} emotes at the pose level — no target animation code is
 * copied. Emote rotations blend over the resting pose and fade in/out at the
 * edges so the vanilla gait is never dampened.
 */
public final class NpcEmoteAnimator {

    private NpcEmoteAnimator() {}

    /**
     * @param model    the humanoid model currently animating the NPC
     * @param emote    the active synced emote ({@link NpcEmote#NONE} → no-op)
     * @param progress normalized emote progress in {@code [0,1]}
     * @param ageInTicks continuous tick age for smooth oscillation
     */
    public static void apply(HumanoidModel<?> model, NpcEmote emote,
                             float progress, float ageInTicks) {
        if (model == null || emote == null || emote == NpcEmote.NONE) return;
        ModelPart[] parts = {model.head, model.hat, model.body, model.leftArm, model.rightArm};
        float[] base = new float[parts.length * 3];
        for (int i = 0; i < parts.length; i++) {
            base[i * 3] = parts[i].xRot;
            base[i * 3 + 1] = parts[i].yRot;
            base[i * 3 + 2] = parts[i].zRot;
        }

        float wave = Mth.sin(ageInTicks * 0.35f);
        switch (emote) {
            case WAVE -> {
                model.rightArmPose = HumanoidModel.ArmPose.THROW_SPEAR;
                model.rightArm.xRot = -(float) Math.PI * 0.9f;
                model.rightArm.zRot = wave * 0.45f;
            }
            case POINT -> {
                model.rightArmPose = HumanoidModel.ArmPose.THROW_SPEAR;
                model.rightArm.xRot = -(float) Math.PI / 2f;
                model.rightArm.zRot = 0f;
            }
            case HUG -> {
                model.leftArm.xRot = -(float) Math.PI / 2.4f;
                model.rightArm.xRot = -(float) Math.PI / 2.4f;
                model.leftArm.zRot = 0.5f;
                model.rightArm.zRot = -0.5f;
            }
            case BOW -> {
                model.body.xRot = 0.55f;
                model.head.xRot = 0.35f;
                model.hat.xRot = 0.35f;
                model.leftArm.xRot = 0.45f;
                model.rightArm.xRot = 0.45f;
            }
            case NO -> {
                model.head.yRot = wave * 0.55f;
                model.hat.yRot = wave * 0.55f;
            }
            case YES -> {
                model.head.xRot = wave * 0.4f;
                model.hat.xRot = wave * 0.4f;
            }
            case AIM -> {
                model.leftArmPose = model.rightArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
            case CRAWL -> {
                model.crouching = true;
                model.body.xRot = 0.65f;
                model.head.xRot = -0.35f;
                model.hat.xRot = -0.35f;
            }
            case DANCE -> {
                float beat = Mth.sin(ageInTicks * 0.5f);
                model.leftArm.xRot = -(float) Math.PI * (beat > 0 ? 0.85f : 0.35f);
                model.rightArm.xRot = -(float) Math.PI * (beat > 0 ? 0.35f : 0.85f);
                model.leftArm.zRot = 0.35f;
                model.rightArm.zRot = -0.35f;
            }
            default -> { }
        }

        // Blend the emote deltas over the captured resting pose; the first and
        // last 15% of the emote fade the deltas in/out.
        float edge = Math.min(progress / 0.15f, (1f - progress) / 0.15f);
        float damp = Mth.clamp(edge, 0f, 1f);
        for (int i = 0; i < parts.length; i++) {
            parts[i].xRot = base[i * 3] + (parts[i].xRot - base[i * 3]) * damp;
            parts[i].yRot = base[i * 3 + 1] + (parts[i].yRot - base[i * 3 + 1]) * damp;
            parts[i].zRot = base[i * 3 + 2] + (parts[i].zRot - base[i * 3 + 2]) * damp;
        }
    }
}
