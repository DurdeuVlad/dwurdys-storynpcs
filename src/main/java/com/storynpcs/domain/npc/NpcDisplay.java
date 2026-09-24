package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NpcDisplay {
    public enum SkinSource {
        TEXTURE,
        PLAYER,
        URL
    }

    public enum BossBarColor {
        PINK,
        BLUE,
        RED,
        GREEN,
        YELLOW,
        PURPLE,
        WHITE
    }

    @JsonProperty
    private String name = "StoryNPC";

    @JsonProperty
    private String title = "";

    @JsonProperty
    private String skinTexture = "storynpcs:textures/entity/default.png";

    /** Selects the authored texture, player profile, or remote URL source. */
    @JsonProperty
    private SkinSource skinSource = SkinSource.TEXTURE;

    @JsonProperty
    private String skinUrl = "";

    @JsonProperty
    private String skinPlayer = "";

    @JsonProperty
    private String cloakTexture = "";

    @JsonProperty
    private String glowTexture = "";

    @JsonProperty
    private boolean overlayGlowing = true;

    @JsonProperty
    private boolean showLayers = true;

    /** 0 = visible, 1 = hidden, 2 = visibility controlled by availability. */
    @JsonProperty
    private int visibility = 0;

    @JsonProperty
    private String modelType = "humanoid";

    /** Optional vanilla/entity model identity; blank means the default humanoid model. */
    @JsonProperty
    private String modelId = "";

    /** Target-compatible base model size, bounded to 1..30. */
    @JsonProperty
    private int modelSize = 5;

    @JsonProperty
    private float scaleX = 1.0f;

    @JsonProperty
    private float scaleY = 1.0f;

    @JsonProperty
    private float scaleZ = 1.0f;

    @JsonProperty
    private boolean showName = true;

    /** 0 = always, 1 = never, 2 = while attacking. */
    @JsonProperty
    private int showNameMode = 0;

    @JsonProperty
    private int tint = 0xFFFFFF;

    @JsonProperty
    private boolean livingAnimation = true;

    /** 0 = normal hitbox, 1 = statue/no-hitbox, 2 = custom projection mode. */
    @JsonProperty
    private int hitboxState = 0;

    /** 0 = hidden, 1 = visible, 2 = visible while attacking. */
    @JsonProperty
    private int bossBarMode = 0;

    @JsonProperty
    private BossBarColor bossBarColor = BossBarColor.PINK;

    public NpcDisplay() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSkinTexture() { return skinTexture; }
    public void setSkinTexture(String skinTexture) { this.skinTexture = skinTexture; }

    public SkinSource getSkinSource() { return skinSource; }
    public void setSkinSource(SkinSource skinSource) {
        this.skinSource = skinSource != null ? skinSource : SkinSource.TEXTURE;
    }

    public String getSkinUrl() { return skinUrl; }
    public void setSkinUrl(String skinUrl) {
        this.skinUrl = boundedText(skinUrl, "skinUrl", 512);
        if (!this.skinUrl.isBlank()) this.skinSource = SkinSource.URL;
    }

    public String getSkinPlayer() { return skinPlayer; }
    public void setSkinPlayer(String skinPlayer) {
        this.skinPlayer = boundedText(skinPlayer, "skinPlayer", 64);
        if (!this.skinPlayer.isBlank()) this.skinSource = SkinSource.PLAYER;
    }

    public String getCloakTexture() { return cloakTexture; }
    public void setCloakTexture(String cloakTexture) {
        this.cloakTexture = boundedText(cloakTexture, "cloakTexture", 512);
    }

    public String getGlowTexture() { return glowTexture; }
    public void setGlowTexture(String glowTexture) {
        this.glowTexture = boundedText(glowTexture, "glowTexture", 512);
    }

    public boolean isOverlayGlowing() { return overlayGlowing; }
    public void setOverlayGlowing(boolean overlayGlowing) { this.overlayGlowing = overlayGlowing; }

    public boolean isShowLayers() { return showLayers; }
    public void setShowLayers(boolean showLayers) { this.showLayers = showLayers; }

    public int getVisibility() { return visibility; }
    public void setVisibility(int visibility) { this.visibility = boundedInt(visibility, 0, 2, "visibility"); }

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = boundedText(modelId, "modelId", 256); }

    public int getModelSize() { return modelSize; }
    public void setModelSize(int modelSize) { this.modelSize = boundedInt(modelSize, 1, 30, "modelSize"); }

    public float getScaleX() { return scaleX; }
    public void setScaleX(float scaleX) { this.scaleX = boundedScale(scaleX, "scaleX"); }

    public float getScaleY() { return scaleY; }
    public void setScaleY(float scaleY) { this.scaleY = boundedScale(scaleY, "scaleY"); }

    public float getScaleZ() { return scaleZ; }
    public void setScaleZ(float scaleZ) { this.scaleZ = boundedScale(scaleZ, "scaleZ"); }

    public boolean isShowName() { return showName; }
    public void setShowName(boolean showName) { this.showName = showName; }

    /** Resolves legacy visibility and the target-compatible name mode together. */
    public boolean isNameVisible(boolean attacking) {
        if (!showName || showNameMode == 1) return false;
        return showNameMode == 0 || attacking;
    }

    public int getShowNameMode() { return showNameMode; }
    public void setShowNameMode(int showNameMode) {
        this.showNameMode = boundedInt(showNameMode, 0, 2, "showNameMode");
    }

    public int getTint() { return tint; }
    public void setTint(int tint) { this.tint = boundedInt(tint, 0, 0xFFFFFF, "tint"); }

    public boolean hasLivingAnimation() { return livingAnimation; }
    public void setLivingAnimation(boolean livingAnimation) { this.livingAnimation = livingAnimation; }

    public int getHitboxState() { return hitboxState; }
    public void setHitboxState(int hitboxState) {
        this.hitboxState = boundedInt(hitboxState, 0, 2, "hitboxState");
    }

    public int getBossBarMode() { return bossBarMode; }
    public void setBossBarMode(int bossBarMode) {
        this.bossBarMode = boundedInt(bossBarMode, 0, 2, "bossBarMode");
    }

    public BossBarColor getBossBarColor() { return bossBarColor; }
    public void setBossBarColor(BossBarColor bossBarColor) {
        this.bossBarColor = bossBarColor != null ? bossBarColor : BossBarColor.PINK;
    }

    private static String boundedText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static int boundedInt(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static float boundedScale(float value, String field) {
        if (!Float.isFinite(value) || value < 0.1f || value > 8.0f) {
            throw new IllegalArgumentException(field + " must be finite and between 0.1 and 8.0");
        }
        return value;
    }
}
