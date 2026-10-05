package com.storynpcs.editor;

import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDisplay;

/**
 * Pure client-side state for {@link com.storynpcs.client.gui.NpcDisplayScreen}
 * (issue #58 — P3-1 display editor coverage). Mirrors the validated
 * {@link NpcDisplay} contract exactly: every picker maps 1:1 to a bounded
 * field, and {@link #apply()} pushes state through the domain setters so
 * server-side validation on the save path stays the single source of truth.
 * The main editor owns name/title (carried forward untouched on apply); this
 * model owns every other display field including skinTexture.
 */
public final class NpcDisplayScreenModel {

    public static final String[] SKIN_SOURCES = {"texture", "player", "url"};
    public static final String[] VISIBILITIES = {"visible", "hidden", "by_availability"};
    public static final String[] NAME_MODES = {"always", "never", "while_attacking"};
    public static final String[] HITBOX_MODES = {"normal", "statue", "custom"};
    public static final String[] BOSS_BAR_MODES = {"hidden", "always", "while_attacking"};
    public static final String[] BOSS_BAR_COLORS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};
    public static final String[] ANIMATION_STANCES = {"normal", "sitting", "lying", "sneaking", "dancing", "aiming"};
    public static final String[] VARIANTS = {"humanoid", "alex", "classic_64x32", "golem",
            "flying", "dragon", "slime", "crystal", "pony"};
    public static final String[] BODY_PARTS = {"beard", "ears", "horns", "snout",
            "tail", "wings", "fin", "skirt", "eyes"};
    public static final String[] PART_BEHAVIORS = {"none", "follow_head", "animated"};

    private final NpcDefinition npc;

    private String skinTexture;
    private String skinUrl;
    private String skinPlayer;
    private String cloakTexture;
    private String glowTexture;
    private String modelType;
    private String modelId;
    private String modelSize;
    private String scaleX;
    private String scaleY;
    private String scaleZ;
    private String tint;

    private int skinSourceIdx;
    private int visibilityIdx;
    private int nameModeIdx;
    private int hitboxIdx;
    private int bossBarModeIdx;
    private int bossBarColorIdx;
    private int stanceIdx;

    private boolean overlayGlowing;
    private boolean showLayers;
    private boolean showName;
    private boolean livingAnimation;

    private int variantIdx;
    /** Selected part being edited; per-part working specs live in partSpecs. */
    private int bodyPartIdx;
    private boolean partEnabled;
    private String partType = "0";
    private String partColor = "FFFFFF";
    private int partBehaviorIdx;
    /** Staged specs across all parts — keyed by BODY_PARTS index. */
    private final java.util.Map<Integer, com.storynpcs.domain.npc.NpcCosmeticPart> partSpecs =
            new java.util.HashMap<>();

    private String statusMessage = "";
    private boolean statusError;

    public NpcDisplayScreenModel(NpcDefinition npc) {
        this.npc = npc;
        NpcDisplay display = npc.getDisplay() != null ? npc.getDisplay() : new NpcDisplay();
        this.skinTexture = display.getSkinTexture();
        this.skinUrl = display.getSkinUrl();
        this.skinPlayer = display.getSkinPlayer();
        this.cloakTexture = display.getCloakTexture();
        this.glowTexture = display.getGlowTexture();
        this.modelType = display.getModelType();
        this.modelId = display.getModelId();
        this.modelSize = String.valueOf(display.getModelSize());
        this.scaleX = trimFloat(display.getScaleX());
        this.scaleY = trimFloat(display.getScaleY());
        this.scaleZ = trimFloat(display.getScaleZ());
        this.tint = String.format("%06X", display.getTint());
        this.skinSourceIdx = switch (display.getSkinSource()) {
            case TEXTURE -> 0;
            case PLAYER -> 1;
            case URL -> 2;
        };
        this.visibilityIdx = display.getVisibility();
        this.nameModeIdx = display.getShowNameMode();
        this.hitboxIdx = display.getHitboxState();
        this.bossBarModeIdx = display.getBossBarMode();
        this.bossBarColorIdx = indexByName(BOSS_BAR_COLORS, display.getBossBarColor().name());
        this.overlayGlowing = display.isOverlayGlowing();
        this.showLayers = display.isShowLayers();
        this.showName = display.isShowName();
        this.livingAnimation = display.hasLivingAnimation();
        NpcAi ai = npc.getAi();
        this.stanceIdx = ai != null
                ? indexByName(ANIMATION_STANCES, ai.getAnimationStance().name()) : 0;
        this.variantIdx = indexByName(VARIANTS, display.getVariant().wire());
        for (var entry : display.getParts().entrySet()) {
            int idx = indexByName(BODY_PARTS, entry.getKey().wire());
            partSpecs.put(idx, entry.getValue());
        }
        loadPartEditor();
    }

    public NpcDefinition getNpc() { return npc; }

    public String getSkinTexture() { return skinTexture; }
    public String getSkinUrl() { return skinUrl; }
    public String getSkinPlayer() { return skinPlayer; }
    public String getCloakTexture() { return cloakTexture; }
    public String getGlowTexture() { return glowTexture; }
    public String getModelType() { return modelType; }
    public String getModelId() { return modelId; }
    public String getModelSize() { return modelSize; }
    public String getScaleX() { return scaleX; }
    public String getScaleY() { return scaleY; }
    public String getScaleZ() { return scaleZ; }
    public String getTint() { return tint; }

    public void setSkinTexture(String v) { skinTexture = v; }
    public void setSkinUrl(String v) { skinUrl = v; }
    public void setSkinPlayer(String v) { skinPlayer = v; }
    public void setCloakTexture(String v) { cloakTexture = v; }
    public void setGlowTexture(String v) { glowTexture = v; }
    public void setModelType(String v) { modelType = v; }
    public void setModelId(String v) { modelId = v; }
    public void setModelSize(String v) { modelSize = v; }
    public void setScaleX(String v) { scaleX = v; }
    public void setScaleY(String v) { scaleY = v; }
    public void setScaleZ(String v) { scaleZ = v; }
    public void setTint(String v) { tint = v; }

    public int getSkinSourceIdx() { return skinSourceIdx; }
    public int getVisibilityIdx() { return visibilityIdx; }
    public int getNameModeIdx() { return nameModeIdx; }
    public int getHitboxIdx() { return hitboxIdx; }
    public int getBossBarModeIdx() { return bossBarModeIdx; }
    public int getBossBarColorIdx() { return bossBarColorIdx; }
    public int getStanceIdx() { return stanceIdx; }

    public void cycleSkinSource(int dir) { skinSourceIdx = Math.floorMod(skinSourceIdx + dir, SKIN_SOURCES.length); }
    public void cycleVisibility(int dir) { visibilityIdx = Math.floorMod(visibilityIdx + dir, VISIBILITIES.length); }
    public void cycleNameMode(int dir) { nameModeIdx = Math.floorMod(nameModeIdx + dir, NAME_MODES.length); }
    public void cycleHitbox(int dir) { hitboxIdx = Math.floorMod(hitboxIdx + dir, HITBOX_MODES.length); }
    public void cycleBossBarMode(int dir) { bossBarModeIdx = Math.floorMod(bossBarModeIdx + dir, BOSS_BAR_MODES.length); }
    public void cycleBossBarColor(int dir) { bossBarColorIdx = Math.floorMod(bossBarColorIdx + dir, BOSS_BAR_COLORS.length); }
    public void cycleStance(int dir) { stanceIdx = Math.floorMod(stanceIdx + dir, ANIMATION_STANCES.length); }
    public void cycleVariant(int dir) { variantIdx = Math.floorMod(variantIdx + dir, VARIANTS.length); }
    public int getVariantIdx() { return variantIdx; }

    /** Switches the edited body part after flushing the current fields into partSpecs. */
    public void cycleBodyPart(int dir) {
        flushPartEditor(false);
        bodyPartIdx = Math.floorMod(bodyPartIdx + dir, BODY_PARTS.length);
        loadPartEditor();
    }

    public int getBodyPartIdx() { return bodyPartIdx; }
    public boolean isPartEnabled() { return partEnabled; }
    public String getPartType() { return partType; }
    public String getPartColor() { return partColor; }
    public int getPartBehaviorIdx() { return partBehaviorIdx; }
    public void setPartEnabled(boolean enabled) { partEnabled = enabled; }
    public void setPartType(String v) { partType = v; }
    public void setPartColor(String v) { partColor = v; }
    public void cyclePartBehavior(int dir) { partBehaviorIdx = Math.floorMod(partBehaviorIdx + dir, PART_BEHAVIORS.length); }

    /** Live view of staged specs — read by the screen for the part list. */
    public java.util.Map<Integer, com.storynpcs.domain.npc.NpcCosmeticPart> getPartSpecs() {
        return java.util.Collections.unmodifiableMap(partSpecs);
    }

    /** Pulls the staged spec for the selected part into the edit fields. */
    private void loadPartEditor() {
        var spec = partSpecs.get(bodyPartIdx);
        partEnabled = spec != null;
        partType = spec != null ? String.valueOf(spec.type()) : "0";
        partColor = spec != null ? String.format("%06X", spec.color()) : "FFFFFF";
        partBehaviorIdx = spec != null
                ? indexByName(PART_BEHAVIORS, spec.behavior().wire()) : 0;
    }

    /**
     * Validates the current fields into a staged spec, or clears it when
     * disabled. In non-strict mode an invalid field surfaces a status error
     * and drops the spec; strict mode (apply) propagates so the atomic commit
     * fails rather than silently losing the part.
     */
    private void flushPartEditor(boolean strict) {
        if (!partEnabled) {
            partSpecs.remove(bodyPartIdx);
            return;
        }
        try {
            var part = com.storynpcs.domain.npc.NpcBodyPart.fromWire(BODY_PARTS[bodyPartIdx]);
            var behavior = com.storynpcs.domain.npc.NpcCosmeticPart.PartBehavior.fromWire(
                    PART_BEHAVIORS[partBehaviorIdx]);
            partSpecs.put(bodyPartIdx, new com.storynpcs.domain.npc.NpcCosmeticPart(
                    part, parseInt(partType, "part type", 0, part.maxType()),
                    parseTint(partColor), behavior));
        } catch (IllegalArgumentException e) {
            if (strict) throw e;
            setStatus(e.getMessage(), true);
            partSpecs.remove(bodyPartIdx);
        }
    }

    public boolean isOverlayGlowing() { return overlayGlowing; }
    public boolean isShowLayers() { return showLayers; }
    public boolean isShowName() { return showName; }
    public boolean isLivingAnimation() { return livingAnimation; }
    public void toggleOverlayGlowing() { overlayGlowing = !overlayGlowing; }
    public void toggleShowLayers() { showLayers = !showLayers; }
    public void toggleShowName() { showName = !showName; }
    public void toggleLivingAnimation() { livingAnimation = !livingAnimation; }

    public String getStatusMessage() { return statusMessage; }
    public boolean isStatusError() { return statusError; }
    public void setStatus(String message, boolean isError) {
        statusMessage = message != null ? message : "";
        statusError = isError;
    }

    /**
     * Validates all widget state and commits it atomically: fields are applied
     * to a detached {@link NpcDisplay} that only replaces the NPC's instance
     * after every setter has succeeded, so an invalid value can never leave a
     * half-written display on the definition. Returns the error string on
     * failure (domain untouched), or null on success.
     */
    public String apply() {
        try {
            NpcDisplay staged = new NpcDisplay();
            // name/title are owned by the main editor — carry them forward.
            if (npc.getDisplay() != null) {
                staged.setName(npc.getDisplay().getName());
                staged.setTitle(npc.getDisplay().getTitle());
            }
            staged.setSkinTexture(skinTexture);
            // The URL/player setters auto-flip skinSource on non-blank input —
            // apply the picker last so an explicit source selection always wins
            // over stale values left in the other fields.
            staged.setSkinUrl(skinUrl);
            staged.setSkinPlayer(skinPlayer);
            staged.setSkinSource(NpcDisplay.SkinSource.valueOf(
                    SKIN_SOURCES[skinSourceIdx].toUpperCase(java.util.Locale.ROOT)));
            staged.setCloakTexture(cloakTexture);
            staged.setGlowTexture(glowTexture);
            staged.setOverlayGlowing(overlayGlowing);
            staged.setShowLayers(showLayers);
            staged.setVisibility(visibilityIdx);
            staged.setModelType(modelType);
            staged.setModelId(modelId);
            staged.setModelSize(parseInt(modelSize, "model size", 1, 30));
            staged.setScaleX(parseScale(scaleX, "scale X"));
            staged.setScaleY(parseScale(scaleY, "scale Y"));
            staged.setScaleZ(parseScale(scaleZ, "scale Z"));
            staged.setShowName(showName);
            staged.setShowNameMode(nameModeIdx);
            staged.setTint(parseTint(tint));
            staged.setLivingAnimation(livingAnimation);
            staged.setHitboxState(hitboxIdx);
            staged.setBossBarMode(bossBarModeIdx);
            staged.setBossBarColor(NpcDisplay.BossBarColor.valueOf(
                    BOSS_BAR_COLORS[bossBarColorIdx].toUpperCase(java.util.Locale.ROOT)));
            staged.setVariant(com.storynpcs.domain.npc.NpcVariant.fromWire(VARIANTS[variantIdx]));
            flushPartEditor(true);
            java.util.EnumMap<com.storynpcs.domain.npc.NpcBodyPart,
                    com.storynpcs.domain.npc.NpcCosmeticPart> stagedParts =
                    new java.util.EnumMap<>(com.storynpcs.domain.npc.NpcBodyPart.class);
            for (var entry : partSpecs.entrySet()) {
                stagedParts.put(entry.getValue().part(), entry.getValue());
            }
            staged.setParts(stagedParts);
            NpcAi.AnimationStance stance = NpcAi.AnimationStance.valueOf(
                    ANIMATION_STANCES[stanceIdx].toUpperCase(java.util.Locale.ROOT));

            // Commit phase — nothing below can fail.
            npc.setDisplay(staged);
            if (npc.getAi() == null) npc.setAi(new NpcAi());
            npc.getAi().setAnimationStance(stance);
            return null;
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), true);
            return e.getMessage();
        }
    }

    /** Positional index of {@code name} inside {@code options} (case-insensitive). */
    private static int indexByName(String[] options, String name) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(name)) return i;
        }
        return 0;
    }

    private static String trimFloat(float value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static int parseInt(String raw, String label, int min, int max) {
        try {
            int v = Integer.parseInt(raw == null ? "" : raw.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid whole number for " + label + ".");
        }
    }

    private static float parseScale(String raw, String label) {
        try {
            float v = Float.parseFloat(raw == null ? "" : raw.trim());
            if (!Float.isFinite(v) || v < 0.1f || v > 8.0f) {
                throw new IllegalArgumentException(label + " must be between 0.1 and 8.0.");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid number for " + label + ".");
        }
    }

    private static int parseTint(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return 0xFFFFFF;
        try {
            int v = Integer.parseInt(normalized, 16);
            if (v < 0 || v > 0xFFFFFF) {
                throw new IllegalArgumentException("tint must be a 24-bit hex color (000000-FFFFFF).");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid hex color for tint.");
        }
    }
}
