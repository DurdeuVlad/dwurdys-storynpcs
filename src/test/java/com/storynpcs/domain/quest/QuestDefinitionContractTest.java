package com.storynpcs.domain.quest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.storynpcs.domain.common.DiagnosticError;
import com.storynpcs.domain.common.NamespacedId;

class QuestDefinitionContractTest {

    @Test
    void sixTargetRepeatModesPlusLegacyAlias() {
        assertThat(Quest.RepeatType.values()).containsExactly(
                Quest.RepeatType.NORMAL, Quest.RepeatType.REPEATABLE, Quest.RepeatType.DAILY,
                Quest.RepeatType.WEEKLY, Quest.RepeatType.RESET, Quest.RepeatType.INSTANT);
        assertThat(Quest.RepeatType.fromString("once")).isEqualTo(Quest.RepeatType.NORMAL);
        assertThat(Quest.RepeatType.fromString("ONCE")).isEqualTo(Quest.RepeatType.NORMAL);
        assertThat(Quest.RepeatType.fromString("WEEKLY")).isEqualTo(Quest.RepeatType.WEEKLY);
        assertThat(Quest.RepeatType.INSTANT.autoCompletes()).isTrue();
        assertThat(Quest.RepeatType.DAILY.autoCompletes()).isFalse();
    }

    @Test
    void dailyBoundaryRespectsPolicyHour() {
        RepeatSchedule schedule = new RepeatSchedule(ZoneId.of("UTC"), 6, java.time.DayOfWeek.MONDAY);
        Instant day1 = Instant.parse("2026-01-05T10:00:00Z"); // Monday 10:00, boundary at 06:00
        Instant sameDay = Instant.parse("2026-01-05T18:00:00Z");
        Instant nextDayAfterBoundary = Instant.parse("2026-01-06T07:00:00Z");
        Instant nextDayBeforeBoundary = Instant.parse("2026-01-06T05:00:00Z");

        assertThat(schedule.canRepeat(Quest.RepeatType.DAILY, day1, sameDay)).isFalse();
        assertThat(schedule.canRepeat(Quest.RepeatType.DAILY, day1, nextDayBeforeBoundary)).isFalse();
        assertThat(schedule.canRepeat(Quest.RepeatType.DAILY, day1, nextDayAfterBoundary)).isTrue();
    }

    @Test
    void weeklyBoundaryRespectsWeekStart() {
        RepeatSchedule schedule = RepeatSchedule.utcDefault(); // Monday week start
        Instant monday = Instant.parse("2026-01-05T10:00:00Z");
        Instant sunday = Instant.parse("2026-01-11T10:00:00Z");
        Instant nextMonday = Instant.parse("2026-01-12T00:30:00Z");
        assertThat(schedule.canRepeat(Quest.RepeatType.WEEKLY, monday, sunday)).isFalse();
        assertThat(schedule.canRepeat(Quest.RepeatType.WEEKLY, monday, nextMonday)).isTrue();
    }

    @Test
    void nonCalendarModesDoNotConsultClock() {
        RepeatSchedule schedule = RepeatSchedule.utcDefault();
        Instant t0 = Instant.EPOCH, t1 = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(schedule.canRepeat(Quest.RepeatType.NORMAL, t0, t1)).isFalse();
        assertThat(schedule.canRepeat(Quest.RepeatType.RESET, t0, t1)).isFalse();
        assertThat(schedule.canRepeat(Quest.RepeatType.REPEATABLE, t0, t1)).isTrue();
        assertThat(schedule.canRepeat(Quest.RepeatType.INSTANT, t0, t1)).isTrue();
        assertThat(schedule.canRepeat(Quest.RepeatType.DAILY, null, t1)).isTrue();
    }

    @Test
    void dependencyValidatorDetectsCyclesAndMissingPrereqs() {
        QuestDependencyValidator validator = new QuestDependencyValidator();
        Quest a = quest("a", "b");
        Quest b = quest("b", "a"); // cycle a -> b -> a
        Quest c = quest("c", "missing");

        var result = validator.validate(List.of(a, b, c));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(DiagnosticError::code)
                .contains("QUEST_DEPENDENCY_CYCLE", "QUEST_MISSING_PREREQUISITE");
    }

    @Test
    void completionOrderIsTopologicalAndDeterministic() {
        QuestDependencyValidator validator = new QuestDependencyValidator();
        Quest base = quest("base");
        Quest mid = quest("mid", "base");
        Quest top = quest("top", "mid");
        List<NamespacedId> order = validator.completionOrder(List.of(top, base, mid));
        assertThat(order).extracting(NamespacedId::toString)
                .containsExactly("storynpcs:base", "storynpcs:mid", "storynpcs:top");
        // Cycle → no valid order.
        assertThat(validator.completionOrder(List.of(quest("x", "y"), quest("y", "x")))).isEmpty();
    }

    @Test
    void customObjectiveCarriesTypedExtensionContract() {
        QuestObjective objective = new QuestObjective("obj", QuestObjective.Type.CUSTOM, "any", 1);
        objective.setCustomType("storynpcs:ritual_witnessed");
        assertThat(objective.getCustomType()).isEqualTo("storynpcs:ritual_witnessed");
        objective.setCustomType(null);
        assertThat(objective.getCustomType()).isEmpty();
    }

    private static Quest quest(String name, String... prereqs) {
        Quest q = new Quest(NamespacedId.of("storynpcs", name), name);
        for (String p : prereqs) {
            q.getPrerequisites().add(NamespacedId.of("storynpcs", p));
        }
        return q;
    }
}
