package com.storynpcs.service;

/** User-visible outcome of an attempted quest completion. */
public record QuestCompletionResult(Outcome outcome, String code, int rewardsApplied) {
    public enum Outcome {
        COMPLETED,
        ALREADY_COMPLETED,
        REJECTED,
        FAILED
    }

    public static QuestCompletionResult completed(int rewardsApplied) {
        return new QuestCompletionResult(Outcome.COMPLETED, "QUEST_COMPLETED", rewardsApplied);
    }

    public static QuestCompletionResult alreadyCompleted() {
        return new QuestCompletionResult(Outcome.ALREADY_COMPLETED, "ALREADY_COMPLETED", 0);
    }

    public static QuestCompletionResult rejected(String code) {
        return new QuestCompletionResult(Outcome.REJECTED, code, 0);
    }

    public static QuestCompletionResult failed(String code, int rewardsApplied) {
        return new QuestCompletionResult(Outcome.FAILED, code, rewardsApplied);
    }
}
