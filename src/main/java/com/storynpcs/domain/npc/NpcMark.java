package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NpcMark {
    @JsonProperty
    private int type = 0; // 0=None, 1=Exclamation, 2=Question, 3=Pointer, etc.

    @JsonProperty
    private int color = 0xFFFFFF;

    @JsonProperty
    private String text = "";

    /** Whether the mark is currently shown; availability resynchronizes visibility. */
    @JsonProperty
    private boolean available = true;

    public NpcMark() {}

    public NpcMark(int type, int color, String text) {
        setType(type);
        setColor(color);
        setText(text);
    }

    public int getType() { return type; }
    public void setType(int type) {
        if (type < 0 || type > 255) {
            throw new IllegalArgumentException("type must be between 0 and 255");
        }
        this.type = type;
    }

    public int getColor() { return color; }
    public void setColor(int color) {
        if (color < 0 || color > 0xFFFFFF) {
            throw new IllegalArgumentException("color must be between 0x000000 and 0xFFFFFF");
        }
        this.color = color;
    }

    public String getText() { return text; }
    public void setText(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("text exceeds 128 characters");
        }
        this.text = normalized;
    }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    /**
     * Clean-room glyph indicator for the mark's type bucket — the target
     * renders texture icons; StoryNPCs projects the same marker intent onto a
     * deterministic text glyph tinted to {@link #color}. Type 0 (None) shows
     * no glyph.
     */
    public String displayGlyph() {
        return switch (type) {
            case 0 -> "";
            case 1 -> "!";
            case 2 -> "?";
            case 3 -> "▼";
            default -> "◆";
        };
    }
}
