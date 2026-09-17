package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NpcMark {
    @JsonProperty
    private int type = 0; // 0=None, 1=Exclamation, 2=Question, 3=Pointer, etc.

    @JsonProperty
    private int color = 0xFFFFFF;

    @JsonProperty
    private String text = "";

    public NpcMark() {}

    public NpcMark(int type, int color, String text) {
        this.type = type;
        this.color = color;
        this.text = text;
    }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
