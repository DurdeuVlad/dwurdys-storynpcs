package com.storynpcs.domain.dialogue;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the directed dialogue graph.
 */
public class DialogueNode {
    @JsonProperty(required = true)
    private String id;

    @JsonProperty(required = true)
    private String text;

    @JsonProperty
    private String sound = "";

    @JsonProperty
    private List<DialogueEdge> options = new ArrayList<>();

    public DialogueNode() {}

    public DialogueNode(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getSound() { return sound; }
    public void setSound(String sound) { this.sound = sound; }

    public List<DialogueEdge> getOptions() { return options; }
    public void setOptions(List<DialogueEdge> options) { this.options = options; }

    public void addOption(DialogueEdge option) {
        this.options.add(option);
    }

    public boolean isTerminal() {
        return options == null || options.isEmpty();
    }
}
