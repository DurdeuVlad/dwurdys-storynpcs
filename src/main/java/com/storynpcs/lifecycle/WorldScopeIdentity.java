package com.storynpcs.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves the durable logical scope of a world's {@code storynpcs/} directory.
 *
 * <p>Logical actor identity must survive the world directory being moved or
 * renamed, so the scope cannot be derived from the absolute path. Instead a
 * stable random token is persisted in {@code scope.id} beside the stores and
 * reused on every subsequent start. For worlds created before {@code scope.id}
 * existed, the scope recorded inside {@code actors/registry.json} is adopted.
 *
 * <p>Fail closed: a {@code scope.id} that exists but is unreadable or
 * malformed, or an actor registry that exists but cannot be read, is an
 * {@link IOException} — the caller must not mint a divergent scope that could
 * later overwrite real identities.
 */
public final class WorldScopeIdentity {
    /** File inside {@code world/storynpcs/} holding the durable scope token. */
    public static final String SCOPE_FILE = "scope.id";

    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final int MAX_SCOPE_LENGTH = 512;
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorldScopeIdentity() {}

    /**
     * Returns the durable scope for {@code storyNpcsDir}, reading {@code scope.id}
     * when present, adopting the existing actor registry's scope for legacy
     * worlds, or minting and persisting a fresh token otherwise.
     *
     * @throws IOException when existing identity evidence cannot be read or is malformed
     */
    public static String resolve(Path storyNpcsDir, Path actorRegistryFile) throws IOException {
        Objects.requireNonNull(storyNpcsDir, "storyNpcsDir");
        Path scopeFile = storyNpcsDir.resolve(SCOPE_FILE);
        if (Files.isRegularFile(scopeFile)) {
            String stored = Files.readString(scopeFile).trim();
            if (!isValidScope(stored)) {
                throw new IOException("actor scope identity file is invalid: " + scopeFile);
            }
            return stored;
        }
        if (Files.exists(scopeFile)) {
            throw new IOException("actor scope identity path is not a regular file: " + scopeFile);
        }

        String scope;
        if (actorRegistryFile != null && Files.exists(actorRegistryFile)) {
            scope = readRegistryScope(actorRegistryFile);
        } else {
            scope = null;
        }
        if (scope == null) {
            scope = "world-" + UUID.randomUUID();
        }
        writeAtomic(scopeFile, scope);
        return scope;
    }

    private static boolean isValidScope(String scope) {
        return scope != null && !scope.isEmpty()
                && scope.length() <= MAX_SCOPE_LENGTH
                && SAFE_SCOPE.matcher(scope).matches();
    }

    /**
     * Reads {@code scopeId} out of an existing actor registry envelope.
     * A registry that exists but cannot be parsed is identity evidence the
     * caller cannot arbitrate — fail closed instead of minting a new scope.
     */
    private static String readRegistryScope(Path actorRegistryFile) throws IOException {
        final JsonNode root;
        try {
            root = JSON.readTree(Files.readString(actorRegistryFile));
        } catch (IOException unreadable) {
            throw new IOException("actor registry exists but is unreadable: " + actorRegistryFile, unreadable);
        }
        JsonNode scopeNode = root == null ? null : root.path("data").path("scopeId");
        if (scopeNode == null || !scopeNode.isTextual()) {
            // Legacy v0 registries are stored unenveloped — the scope token
            // lives at the document root instead of under "data".
            scopeNode = root == null ? null : root.path("scopeId");
        }
        if (scopeNode == null || !scopeNode.isTextual() || !isValidScope(scopeNode.asText().trim())) {
            throw new IOException("actor registry exists but carries no valid scopeId: " + actorRegistryFile);
        }
        return scopeNode.asText().trim();
    }

    private static void writeAtomic(Path file, String contents) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.writeString(temporary, contents,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temporary, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
