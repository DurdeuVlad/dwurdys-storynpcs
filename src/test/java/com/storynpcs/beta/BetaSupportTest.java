package com.storynpcs.beta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BetaSupportTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Orientation lines mention both tester actions and the quickstart")
    void orientationCoversActions() {
        List<String> lines = BetaSupport.orientationLines();
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("beta report"), "orientation must teach the report action");
        assertTrue(joined.contains("beta feedback"), "orientation must teach the feedback action");
        assertTrue(joined.contains("quickstart"), "orientation should point testers at the demo");
        assertTrue(joined.contains("world/storynpcs/beta/"),
                "orientation must say where reports land");
    }

    @Test
    @DisplayName("Report filenames are deterministic, timestamped, and filesystem-safe")
    void reportFileNameSafe() {
        Instant t = Instant.parse("2026-10-06T12:34:56Z");
        assertEquals("beta-report-20261006-123456-Alex.md",
                BetaSupport.reportFileName(t, "Alex"));
        String safe = BetaSupport.reportFileName(t, "we/ird: name?");
        assertTrue(safe.matches("beta-report-20261006-123456-[\\w-]+\\.md"),
                "hostile reporter names must be sanitized, got: " + safe);
        assertEquals("beta-report-20261006-123456-unknown.md",
                BetaSupport.reportFileName(t, "   "));
        assertEquals("beta-report-20261006-123456-unknown.md",
                BetaSupport.reportFileName(t, null));
    }

    @Test
    @DisplayName("Feedback lines carry timestamp and reporter, flattened to one line")
    void feedbackLineFormat() {
        Instant t = Instant.parse("2026-10-06T12:00:00Z");
        assertEquals("[2026-10-06T12:00:00Z] Alex: the wand did nothing",
                BetaSupport.feedbackLine(t, "Alex", "the wand did nothing"));
        assertEquals("[2026-10-06T12:00:00Z] Alex: line one line two",
                BetaSupport.feedbackLine(t, "Alex", "line one\nline two\r\n"));
    }

    @Test
    @DisplayName("Report contains versions, reporter, content counts, player context, and note")
    void reportContents() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("npcs", 7);
        counts.put("dialogues", 3);
        String report = BetaSupport.buildReport("0.11.0-beta.1", "1.21.1", "21.1.248",
                Instant.parse("2026-10-06T12:00:00Z"), "Alex", counts,
                List.of("position: 10 64 -20 in minecraft:overworld",
                        "quests: 2 active, 1 completed"),
                "the trader ate my emeralds");

        assertTrue(report.contains("storynpcs: 0.11.0-beta.1"));
        assertTrue(report.contains("minecraft: 1.21.1"));
        assertTrue(report.contains("reporter: Alex"));
        assertTrue(report.contains("npcs: 7"));
        assertTrue(report.contains("position: 10 64 -20"));
        assertTrue(report.contains("quests: 2 active, 1 completed"));
        assertTrue(report.contains("the trader ate my emeralds"));
        assertTrue(report.contains("Attach"), "report must tell the tester what to send");
    }

    @Test
    @DisplayName("Report degrades honestly with no registry and no player")
    void reportDegradesGracefully() {
        String report = BetaSupport.buildReport("dev", "1.21.1", "unknown",
                Instant.parse("2026-10-06T12:00:00Z"), "console",
                Map.of(), List.of(), null);
        assertTrue(report.contains("registry unavailable"));
        assertTrue(report.contains("none (run from console or before world load)"));
        assertTrue(report.contains("—"), "missing note renders as an em dash");
    }

    @Test
    @DisplayName("writeReport creates reports/, writes content, and never overwrites")
    void writeReportRoundTrip() throws IOException {
        Path first = BetaSupport.writeReport(tempDir, "beta-report-20261006-120000-Alex.md", "one");
        assertEquals(tempDir.resolve("reports/beta-report-20261006-120000-Alex.md"), first);
        assertEquals("one", Files.readString(first));
        assertFalse(Files.exists(tempDir.resolve("reports/beta-report-20261006-120000-Alex.md.tmp")),
                "no .tmp residue after atomic write");

        Path second = BetaSupport.writeReport(tempDir, "beta-report-20261006-120000-Alex.md", "two");
        assertNotEquals(first, second, "same-timestamp reports must not overwrite each other");
        assertEquals("two", Files.readString(second));
        assertEquals("one", Files.readString(first));
    }

    @Test
    @DisplayName("appendFeedback appends one line per call to feedback.log")
    void feedbackRoundTrip() throws IOException {
        BetaSupport.appendFeedback(tempDir, "[t1] Alex: first");
        BetaSupport.appendFeedback(tempDir, "[t2] Sam: second");
        List<String> lines = Files.readAllLines(tempDir.resolve("feedback.log"));
        assertEquals(List.of("[t1] Alex: first", "[t2] Sam: second"), lines);
    }
}
