package com.storynpcs.editor;

public class VisualNode {
    private String id;
    private String text;
    private String sound = "";
    private String speaker = "";
    private double x;
    private double y;
    private double width = 160;
    private double height = 80;
    private boolean entryNode;

    public VisualNode() {}

    public VisualNode(String id, String text, double x, double y) {
        this.id = id;
        this.text = text != null ? text : "";
        this.x = x;
        this.y = y;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSound() { return sound; }
    public void setSound(String sound) { this.sound = sound != null ? sound : ""; }

    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker != null ? speaker : ""; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }

    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }

    public boolean isEntryNode() { return entryNode; }
    public void setEntryNode(boolean entryNode) { this.entryNode = entryNode; }

    public boolean contains(double px, double py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    public double getCenterX() {
        return x + width / 2.0;
    }

    public double getCenterY() {
        return y + height / 2.0;
    }
}