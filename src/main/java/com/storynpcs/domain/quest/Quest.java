package com.storynpcs.domain.quest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

import java.util.ArrayList;
import java.util.List;

public class Quest {
    public enum RepeatType {
        /** Completes once; no repetition without a manual/server reset. */
        NORMAL,
        /** Freely repeatable. */
        REPEATABLE,
        /** Repeatable once per local day boundary. */
        DAILY,
        /** Resets on the server's next ISO week boundary (Monday, server time zone). */
        WEEKLY,
        /**
         * Resets on a schedule, like {@link #DAILY}. Per-player restart eligibility
         * mirrors DAILY; this does not yet implement the target's server-wide
         * simultaneous-reset-for-all-players semantics — per-player repeat eligibility is computed by {@link com.storynpcs.domain.quest.RepeatSchedule}; explicit RESET mutations handle reopening.
         */
        RESET,
        /** Completes instantly on objective satisfaction without a turn-in step; immediately restartable like REPEATABLE. */
        INSTANT;

        /** Whether completion happens automatically when objectives are satisfied. */
        public boolean autoCompletes() {
            return this == INSTANT;
        }

        /** Legacy YAML alias: {@code repeatType: ONCE} maps to NORMAL. */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static RepeatType fromString(String value) {
            if (value != null && value.equalsIgnoreCase("once")) {
                return NORMAL;
            }
            return valueOf(value);
        }
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
    private RepeatType repeatType = RepeatType.NORMAL;

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
    public void setRepeatType(RepeatType repeatType) {
        this.repeatType = repeatType != null ? repeatType : RepeatType.NORMAL;
    }

    public List<NamespacedId> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(List<NamespacedId> prerequisites) { this.prerequisites = prerequisites; }

    public List<QuestObjective> getObjectives() { return objectives; }
    public void setObjectives(List<QuestObjective> objectives) { this.objectives = objectives; }

    public List<QuestReward> getRewards() { return rewards; }
    public void setRewards(List<QuestReward> rewards) { this.rewards = rewards; }
}
