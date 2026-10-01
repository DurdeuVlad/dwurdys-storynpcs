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

    /** Field shape after annotation stripping: modifiers/type words, a name, optional initializer. */
    private static final Pattern FIELD_SHAPE = Pattern.compile(
            "^(?:[\\w.<>\\[\\],?]+\\s+)+\\w+\\s*(?:=.*)?$", Pattern.DOTALL);
    private static final Pattern STATIC = Pattern.compile("\\bstatic\\b");
    private static final Pattern FINAL = Pattern.compile("\\bfinal\\b");
    private static final Pattern TYPE_KEYWORD = Pattern.compile(
            "\\b(class|interface|enum|record|@interface)\\b");
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+(?:\\s*\\([^)]*\\))?");

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

    @Test
    void scannerCatchesMutableStaticEvasionShapes() {
        // Shapes that defeated the original per-line regex: annotation-prefixed
        // declarations, line-split declarations, single-line nested types, and
        // comment/string confusion.
        assertThat(isMutableStaticField("@Deprecated private static int counter")).isTrue();
        assertThat(isMutableStaticField("private static\n    int counter")).isTrue();
        assertThat(isMutableStaticField("static int counter")).isTrue();
        assertThat(isMutableStaticField("private static Map<String, Integer> counts = new HashMap<>()"))
                .isTrue();
        assertThat(isMutableStaticField("public static var state")).isTrue();

        // And the surrounding file context: a single-line nested class's field
        // must still surface after statement splitting.
        String nested = stripCommentsAndLiterals(
                "class Outer { static class Holder { private static int counter; } }");
        boolean found = false;
        for (String fragment : nested.split("[;{}]")) {
            found |= isMutableStaticField(fragment);
        }
        assertThat(found).isTrue();
    }

    @Test
    void scannerIgnoresImmutableAndNonFieldStatics() {
        assertThat(isMutableStaticField("private static final int LIMIT = 5")).isFalse();
        assertThat(isMutableStaticField("public static final Map<String, Integer> M = Map.of()")).isFalse();
        assertThat(isMutableStaticField("import static com.x.Y")).isFalse();
        assertThat(isMutableStaticField("private static int compute()")).isFalse();
        assertThat(isMutableStaticField("static class Holder")).isFalse();
        assertThat(isMutableStaticField("static")).isFalse();
        assertThat(isMutableStaticField("")).isFalse();
    }

    @Test
    void literalsAndCommentsCannotConfuseTheScanner() {
        // A '/*' inside a string literal must not open a fake block comment that
        // suppresses later lines, and '//' inside a string must not truncate.
        String source = "class T {\n"
                + "    String s = \"/* not a comment */\";\n"
                + "    String u = \"// not a comment\";\n"
                + "    private static int counter;\n"
                + "    // a real comment mentions static int ignored\n"
                + "    /* block mentions static int ignoredToo */\n"
                + "    private static final int LIMIT = 3;\n"
                + "}";
        String sanitized = stripCommentsAndLiterals(source);
        boolean sawMutable = false, sawLimit = false;
        for (String fragment : sanitized.split("[;{}]")) {
            sawMutable |= isMutableStaticField(fragment);
            sawLimit |= fragment.contains("LIMIT") && isMutableStaticField(fragment);
        }
        assertThat(sawMutable).isTrue();
        assertThat(sawLimit).isFalse();
    }

    private void scanFile(Path file, List<String> violations) throws IOException {
        // Sanitize the whole file first so comments and string/char literals
        // cannot hide declarations or fake block-comment state, then split on
        // statement/scope boundaries so multi-line declarations and single-line
        // nested types cannot evade per-line matching.
        String sanitized = stripCommentsAndLiterals(Files.readString(file));
        int statementStart = 0;
        int line = 1;
        for (int i = 0; i <= sanitized.length(); i++) {
            char c = i < sanitized.length() ? sanitized.charAt(i) : ';';
            if (c == '\n') {
                line++;
                continue;
            }
            if (c != ';' && c != '{' && c != '}') {
                continue;
            }
            String fragment = sanitized.substring(statementStart, i).trim();
            if (isMutableStaticField(fragment)) {
                violations.add(file + ":" + line
                        + " -> mutable static field: " + fragment.replaceAll("\\s+", " "));
            }
            statementStart = i + 1;
        }
    }

    /**
     * Detects a {@code static} field declaration lacking {@code final} in one
     * logical statement fragment (no braces or semicolons inside). Package-private
     * for direct unit tests of evasion shapes.
     */
    static boolean isMutableStaticField(String fragment) {
        String f = ANNOTATION.matcher(fragment).replaceAll("").trim();
        if (f.isEmpty() || f.startsWith("import ") || f.startsWith("package ")) {
            return false;
        }
        if (!STATIC.matcher(f).find() || FINAL.matcher(f).find()
                || TYPE_KEYWORD.matcher(f).find()) {
            return false;
        }
        return FIELD_SHAPE.matcher(f).matches();
    }

    /**
     * Blanks out comments and string/char literals so neither can disguise code
     * or corrupt comment state for later lines.
     */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean blockComment = false, lineComment = false, inString = false, inChar = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inString || inChar) {
                out.append(c == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (c == '"') {
                inString = true;
                out.append(' ');
            } else if (c == '\'') {
                inChar = true;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
