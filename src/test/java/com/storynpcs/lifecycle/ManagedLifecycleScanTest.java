package com.storynpcs.lifecycle;

import com.storynpcs.StoryNpcs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Managed-lifecycle gate: the production tree may not grow new mutable static
 * fields. State must live on injected instances (service, registry, repository,
 * session) or on lifecycle-owned handles (the level attachment installed at
 * level load and resolved through {@link com.storynpcs.StoryNpcsAccess}) — no
 * bootstrap singleton may return.
 */
class ManagedLifecycleScanTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** static field declarations: any access modifier, optional extra modifiers, type, name, then ';' or '='. */
    private static final Pattern MUTABLE_STATIC_FIELD = Pattern.compile(
            "^\\s*(?:public|protected|private)?\\s*static\\s+(?!.*\\bfinal\\s)(?!class\\b|interface\\b|enum\\b|record\\b|@interface\\b)"
                    + "[\\w.<>\\[\\],?\\s]+?\\s+(\\w+)\\s*(?:;|=).*");

    @Test
    void unattachedLevelResolvesNoModInstance() {
        // Null-safe resolution: paths that legitimately run before a level
        // exists (early dispatch, class init) receive null, never throw.
        assertThat(com.storynpcs.StoryNpcsAccess.mod((net.minecraft.world.level.LevelAccessor) null))
                .isNull();
        assertThat(com.storynpcs.StoryNpcsAccess.mod((net.minecraft.server.MinecraftServer) null))
                .isNull();
        assertThat(com.storynpcs.StoryNpcsAccess.mod((net.minecraft.world.entity.Entity) null))
                .isNull();
        assertThatThrownBy(() -> com.storynpcs.StoryNpcsAccess.require(
                (net.minecraft.world.level.LevelAccessor) null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clientCoordinatorDoesNotOwnMutableAuthoringStateStatically() throws IOException {
        String clientSource = Files.readString(MAIN_SOURCES.resolve(
                Path.of("com", "storynpcs", "client", "StoryNpcsClient.java")));

        assertThat(clientSource).doesNotContain("static final com.storynpcs.editor.hub.AuthoringHub");
    }

    @Test
    void testInstancesAreIndependentAndUnpublished() {
        // Each fixture construction is a self-contained instance — callers
        // hold the reference; nothing reaches a global channel.
        StoryNpcs first = StoryNpcs.createForTesting();
        StoryNpcs second = StoryNpcs.createForTesting();
        assertThat(first).isNotSameAs(second);
        assertThat(first.getRegistry()).isNotSameAs(second.getRegistry());
        assertThat(first.getEventPublisher()).isNotSameAs(second.getEventPublisher());
        assertThat(first.getRuntimeSessions()).isNotSameAs(second.getRuntimeSessions());
    }

    @Test
    void productionSourcesContainNoMutableStaticFields() throws IOException {
        assumeTrue(Files.isDirectory(MAIN_SOURCES),
                "main sources are unavailable in this execution context");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanFile(file, violations);
            }
        }
        assertThat(violations)
                .as("mutable static fields must not reappear; hold state on managed instances "
                        + "or lifecycle-owned level attachments.")
                .isEmpty();
    }

    private void scanFile(Path file, List<String> violations) throws IOException {
        String fileName = file.getFileName().toString();
        boolean inBlockComment = false;
        int lineNo = 0;
        for (String rawLine : Files.readAllLines(file)) {
            lineNo++;
            String line = rawLine;
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                if (inBlockComment) {
                    if (i + 1 < line.length() && line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                        inBlockComment = false;
                        i++;
                    }
                } else if (i + 1 < line.length() && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                    inBlockComment = true;
                    i++;
                } else if (i + 1 < line.length() && line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                    break;
                } else {
                    code.append(line.charAt(i));
                }
            }
            var matcher = MUTABLE_STATIC_FIELD.matcher(code.toString().trim());
            if (matcher.matches()) {
                violations.add(file + ":" + lineNo + " -> static field '" + matcher.group(1) + "'");
            }
        }
    }
}
