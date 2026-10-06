package com.storynpcs.beta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Beta-tester support surface behind {@code /storynpcs beta}: orientation
 * text, diagnostic report assembly, and feedback capture. Pure formatting
 * and filesystem mechanics only — the command adapter supplies the data.
 */
public final class BetaSupport {

    /** Directory under {@code world/storynpcs/} holding beta reports and feedback. */
    public static final String BETA_DIR = "beta";
    public static final String REPORTS_DIR = "reports";
    public static final String FEEDBACK_FILE = "feedback.log";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private BetaSupport() {}

    /** Orientation lines for the bare {@code /storynpcs beta} command. */
    public static List<String> orientationLines() {
        return List.of(
                "§6--- StoryNPCs Beta ---",
                "§fYou are testing a beta build — thank you!",
                "§e/storynpcs beta report [note] §7- Save a diagnostic snapshot for the devs (gathers everything for you)",
                "§e/storynpcs beta feedback <text> §7- Leave a quick note for the devs",
                "§e/storynpcs quickstart §7- Spawn the demo NPC and all wands",
                "§7Reports land in world/storynpcs/beta/ — your server owner can forward them.");
    }

    /** Deterministic, filesystem-safe report filename: {@code beta-report-<stamp>-<reporter>.md}. */
    public static String reportFileName(Instant timestamp, String reporter) {
        String safe = reporter == null ? ""
                : reporter.replaceAll("[^\\w-]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");
        if (safe.isBlank()) safe = "unknown";
        return "beta-report-" + FILE_STAMP.format(timestamp) + "-" + safe + ".md";
    }

    /** Single-line feedback entry; newlines are flattened so the log stays one-entry-per-line. */
    public static String feedbackLine(Instant timestamp, String reporter, String text) {
        String flat = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        return "[" + timestamp + "] " + reporter + ": " + flat;
    }

    /**
     * Assemble the diagnostic bundle a tester sends back with a bug report.
     * Everything a dev needs to reproduce — no chores left for the tester.
     */
    public static String buildReport(String modVersion, String minecraftVersion,
            String loaderVersion, Instant timestamp, String reporter,
            Map<String, Integer> contentCounts, List<String> playerContext, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("# StoryNPCs beta report\n\n");
        sb.append("- generated: ").append(timestamp).append('\n');
        sb.append("- reporter: ").append(reporter).append('\n');
        sb.append("- storynpcs: ").append(modVersion).append('\n');
        sb.append("- minecraft: ").append(minecraftVersion).append('\n');
        sb.append("- neoforge: ").append(loaderVersion).append('\n');

        sb.append("\n## Player context\n\n");
        if (playerContext == null || playerContext.isEmpty()) {
            sb.append("none (run from console or before world load)\n");
        } else {
            for (String line : playerContext) sb.append("- ").append(line).append('\n');
        }

        sb.append("\n## Content loaded\n\n");
        if (contentCounts == null || contentCounts.isEmpty()) {
            sb.append("registry unavailable (run before world load)\n");
        } else {
            for (var entry : contentCounts.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }

        sb.append("\n## Tester note\n\n");
        sb.append(note == null || note.isBlank() ? "—" : note.strip()).append('\n');

        sb.append("\n## Attach\n\n");
        sb.append("Send this file plus a screenshot of the problem, if you have one.\n");
        return sb.toString();
    }

    /**
     * Write a report atomically ({@code .tmp} → rename) into {@code <betaDir>/reports/}.
     * Same-name collisions get a {@code -2}, {@code -3}, … suffix rather than overwriting.
     */
    public static Path writeReport(Path betaDir, String fileName, String content) throws IOException {
        Path dir = betaDir.resolve(REPORTS_DIR);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        for (int i = 2; Files.exists(target); i++) {
            target = dir.resolve(fileName.replace(".md", "-" + i + ".md"));
        }
        writeAtomic(target, content);
        return target;
    }

    /** Append one line to {@code <betaDir>/feedback.log}, creating it on first use. */
    public static Path appendFeedback(Path betaDir, String line) throws IOException {
        Files.createDirectories(betaDir);
        Path file = betaDir.resolve(FEEDBACK_FILE);
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
