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
 * The main editor owns name/title/skinTexture; this model owns everything
 * else so the two forms cannot clobber each other.
 */
public final class NpcDisplayScreenModel {

    public static final String[] SKIN_SOURCES = {"texture", "player", "url"};
    public static final String[] VISIBILITIES = {"visible", "hidden", "by_availability"};
    public static final String[] NAME_MODES = {"always", "never", "while_attacking"};
    public static final String[] HITBOX_MODES = {"normal", "statue", "custom"};
    public static final String[] BOSS_BAR_MODES = {"hidden", "always", "while_attacking"};
    public static final String[] BOSS_BAR_COLORS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};
    public static final String[] ANIMATION_STANCES = {"normal", "sitting", "lying", "sneaking", "dancing", "aiming"};

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
        this.bossBarColorIdx = display.getBossBarColor().ordinal();
        this.overlayGlowing = display.isOverlayGlowing();
        this.showLayers = display.isShowLayers();
        this.showName = display.isShowName();
        this.livingAnimation = display.hasLivingAnimation();
        NpcAi ai = npc.getAi();
        this.stanceIdx = ai != null ? ai.getAnimationStance().ordinal() : 0;
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
     * Pushes all widget state into the NPC's display/ai blocks through the
     * domain setters. Returns an error string on the first invalid field, or
     * null on success — the caller aborts the save on a non-null result so an
     * invalid value can never leave the editor.
     */
    public String apply() {
        try {
            if (npc.getDisplay() == null) npc.setDisplay(new NpcDisplay());
            if (npc.getAi() == null) npc.setAi(new NpcAi());
            NpcDisplay display = npc.getDisplay();

            display.setSkinTexture(blankToDefault(skinTexture, "storynpcs:textures/entity/default.png"));
            // The URL/player setters auto-flip skinSource on non-blank input —
            // apply the picker last so an explicit source selection always wins
            // over stale values left in the other fields.
            display.setSkinUrl(skinUrl);
            display.setSkinPlayer(skinPlayer);
            display.setSkinSource(NpcDisplay.SkinSource.valueOf(SKIN_SOURCES[skinSourceIdx].toUpperCase()));
            display.setCloakTexture(cloakTexture);
            display.setGlowTexture(glowTexture);
            display.setOverlayGlowing(overlayGlowing);
            display.setShowLayers(showLayers);
            display.setVisibility(visibilityIdx);
            display.setModelType(modelType);
            display.setModelId(modelId);
            display.setModelSize(parseInt(modelSize, "model size", 1, 30));
            display.setScaleX(parseScale(scaleX, "scale X"));
            display.setScaleY(parseScale(scaleY, "scale Y"));
            display.setScaleZ(parseScale(scaleZ, "scale Z"));
            display.setShowName(showName);
            display.setShowNameMode(nameModeIdx);
            display.setTint(parseTint(tint));
            display.setLivingAnimation(livingAnimation);
            display.setHitboxState(hitboxIdx);
            display.setBossBarMode(bossBarModeIdx);
            display.setBossBarColor(NpcDisplay.BossBarColor.valueOf(
                    BOSS_BAR_COLORS[bossBarColorIdx].toUpperCase()));
            npc.getAi().setAnimationStance(NpcAi.AnimationStance.valueOf(
                    ANIMATION_STANCES[stanceIdx].toUpperCase()));
            return null;
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), true);
            return e.getMessage();
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
