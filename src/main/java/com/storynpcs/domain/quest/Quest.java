package com.storynpcs.domain.quest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.ArrayList;
import java.util.List;

public class Quest {
    public enum RepeatType {
        ONCE,
        REPEATABLE,
        DAILY
    }

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private String title;

    @JsonProperty
    private String description = "";

    @JsonProperty
    private String category = "general";

    @JsonProperty
    private RepeatType repeatType = RepeatType.ONCE;

    @JsonProperty
    private List<NamespacedId> prerequisites = new ArrayList<>();

    @JsonProperty
    private List<QuestObjective> objectives = new ArrayList<>();

    @JsonProperty
    private List<QuestReward> rewards = new ArrayList<>();

    public Quest() {}

    public Quest(NamespacedId id, String title) {
        this.id = id;
        this.title = title;
    }

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public RepeatType getRepeatType() { return repeatType; }
    public void setRepeatType(RepeatType repeatType) { this.repeatType = repeatType; }

    public List<NamespacedId> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(List<NamespacedId> prerequisites) { this.prerequisites = prerequisites; }

    public List<QuestObjective> getObjectives() { return objectives; }
    public void setObjectives(List<QuestObjective> objectives) { this.objectives = objectives; }

    public List<QuestReward> getRewards() { return rewards; }
    public void setRewards(List<QuestReward> rewards) { this.rewards = rewards; }
}
