package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NpcDisplay {
    @JsonProperty
    private String name = "StoryNPC";

    @JsonProperty
    private String title = "";

    @JsonProperty
    private String skinTexture = "storynpcs:textures/entity/default.png";

    @JsonProperty
    private String modelType = "humanoid";

    @JsonProperty
    private float scaleX = 1.0f;

    @JsonProperty
    private float scaleY = 1.0f;

    @JsonProperty
    private float scaleZ = 1.0f;

    @JsonProperty
    private boolean showName = true;

    public NpcDisplay() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSkinTexture() { return skinTexture; }
    public void setSkinTexture(String skinTexture) { this.skinTexture = skinTexture; }

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    public float getScaleX() { return scaleX; }
    public void setScaleX(float scaleX) { this.scaleX = scaleX; }

    public float getScaleY() { return scaleY; }
    public void setScaleY(float scaleY) { this.scaleY = scaleY; }

    public float getScaleZ() { return scaleZ; }
    public void setScaleZ(float scaleZ) { this.scaleZ = scaleZ; }

    public boolean isShowName() { return showName; }
    public void setShowName(boolean showName) { this.showName = showName; }
}
