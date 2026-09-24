package com.storynpcs.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.role.trader.TradeListing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes domain definitions back to YAML on disk.
 * Uses the same atomic .tmp -> rename discipline as the persistence repositories,
 * so a crash mid-write never leaves a truncated definition file. A directory-local
 * lifecycle-owned coordinator serializes in-process callers and an on-disk file lock
 * serializes cooperating processes.
 * Path checks do not make validation and rename indivisible against a hostile local
 * filesystem actor; the definitions root and its parents must remain admin-controlled.
 */
public class YamlDefinitionWriter {

    private static final Pattern WINDOWS_DEVICE_NAME = Pattern.compile(
            "(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");
    private static final Pattern LEGACY_UNSAFE_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9_.-]");
    private static final Pattern SAFE_FILENAME_STEM = Pattern.compile("[a-zA-Z0-9_.@%-]+");
    private static final Pattern SAFE_SUBDIRECTORY = Pattern.compile("[a-zA-Z0-9_.-]+");

    /** Runtime listing uses belong in TradeStateRepository, never in YAML content. */
    @JsonIgnoreProperties("uses")
    private abstract static class TradeListingContentMixin {}

    private final ObjectMapper mapper;
    private final DefinitionWriteCoordinator writeCoordinator;

    /** Creates a writer with a private coordinator; share a coordinator across concurrent writer instances. */
    public YamlDefinitionWriter() {
        this(new DefinitionWriteCoordinator());
    }

    /** Uses the supplied lifecycle-owned coordinator to serialize local and cross-process writes. */
    public YamlDefinitionWriter(DefinitionWriteCoordinator writeCoordinator) {
        this.writeCoordinator = java.util.Objects.requireNonNull(writeCoordinator, "writeCoordinator");
        this.mapper = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
        this.mapper.addMixIn(TradeListing.class, TradeListingContentMixin.class);
    }

    /** Writes a dialogue to {@code <definitionsRoot>/dialogues/<path>.yaml}. */
    public Path writeDialogue(Path definitionsRoot, DialogueGraph graph) throws IOException {
        return writeDefinition(definitionsRoot, "dialogues", fileNameFor(graph.getId()), graph);
    }

    /** Atomically writes {@code value} to {@code <root>/<subdir>/<baseName>.yaml}. */
    public Path writeDefinition(Path root, String subdir, String baseName, Object value) throws IOException {
        return writeDefinition(root, subdir, baseName, value, List.of());
    }

    /**
     * Atomically writes a definition, preferring source files recorded by the loader.
     * Supplying loader-indexed paths avoids rescanning the full definition catalog on every edit.
     */
    public Path writeDefinition(Path root, String subdir, String baseName, Object value,
                                List<Path> indexedDefinitionFiles) throws IOException {
        if (subdir == null || !SAFE_SUBDIRECTORY.matcher(subdir).matches()
                || ".".equals(subdir) || "..".equals(subdir)
                || WINDOWS_DEVICE_NAME.matcher(subdir).matches()) {
            throw new IOException("Definition subdirectory must be a safe, single path segment");
        }
        if (baseName == null || !SAFE_FILENAME_STEM.matcher(baseName).matches()) {
            throw new IOException("Definition filename must be a safe, non-empty filename stem");
        }

        Path normalizedRoot = root.normalize();
        Path dir = normalizedRoot.resolve(subdir).normalize();
        if (!dir.startsWith(normalizedRoot)) {
            throw new IOException("Definition subdirectory escapes the definitions root");
        }
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("Definition type directory must not be a symbolic link: " + dir);
        }
        Files.createDirectories(dir);
        Path realRoot = root.toRealPath();
        Path realDirectory = dir.toRealPath();
        if (!realDirectory.startsWith(realRoot)) {
            throw new IOException("Definition type directory resolves outside the definitions root: " + dir);
        }
        JsonNode tree = mapper.valueToTree(value);
        if (tree == null || !tree.isObject()) {
            throw new IOException("Definition YAML root must be an object");
        }
        if (indexedDefinitionFiles == null) {
            throw new IOException("Indexed definition file list cannot be null");
        }

        NamespacedId definitionId = readDefinitionId(tree, "new definition");
        Path requestedTarget = dir.resolve(baseName + ".yaml");
        ((ObjectNode) tree).put(DefinitionSchema.VERSION_FIELD, DefinitionSchema.CURRENT_VERSION);

        try (DefinitionWriteLock ignored = writeCoordinator.acquire(dir)) {
            Path target = resolveTarget(root, dir, realRoot, realDirectory, requestedTarget,
                    definitionId, indexedDefinitionFiles);
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), tree);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                deleteTemporaryFile(tmp, failure);
                throw failure;
            } catch (RuntimeException failure) {
                deleteTemporaryFile(tmp, failure);
                throw failure;
            }
            return target;
        }
    }

    /**
     * Chooses a path without overwriting a different definition. Existing path-only files from
     * older versions are reused only when their serialized ID proves they belong to this value.
     */
    private Path resolveTarget(Path root, Path dir, Path realRoot, Path realDirectory, Path requestedTarget,
                               NamespacedId definitionId, List<Path> indexedDefinitionFiles) throws IOException {
        Map<Path, Path> candidates = new LinkedHashMap<>();
        for (Path indexedFile : indexedDefinitionFiles) {
            if (indexedFile == null) {
                throw new IOException("Indexed definition file list contains a null path");
            }
            addCandidate(root, realRoot, realDirectory, candidates, indexedFile);
            if (!Files.exists(indexedFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Indexed source file no longer exists for " + definitionId
                        + "; reload definitions before saving: " + indexedFile);
            }
            if (Files.isSymbolicLink(indexedFile)) {
                throw new IOException("Indexed definition file must not be a symbolic link: " + indexedFile);
            }
            if (!definitionId.equals(readDefinitionId(indexedFile))) {
                throw new IOException("Indexed source file no longer defines " + definitionId
                        + "; reload definitions before saving: " + indexedFile);
            }
        }

        Path legacyTarget = dir.resolve(legacyFileNameFor(definitionId) + ".yaml");
        Path qualifiedTarget = dir.resolve(qualifiedFileNameFor(definitionId) + ".yaml");
        addCandidate(root, realRoot, realDirectory, candidates, requestedTarget);
        addCandidate(root, realRoot, realDirectory, candidates, legacyTarget);
        addCandidate(root, realRoot, realDirectory, candidates, qualifiedTarget);

        List<Path> existingDefinitions = new java.util.ArrayList<>();
        for (Path candidate : candidates.values()) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(candidate)) {
                throw new IOException("Definition file must not be a symbolic link: " + candidate);
            }
            Path candidateParent = candidate.getParent().toRealPath();
            if (!candidateParent.equals(realRoot) && !candidateParent.startsWith(realDirectory)) {
                throw new IOException("Definition file is outside the flat or typed definition directory: " + candidate);
            }
            if (definitionId.equals(readDefinitionId(candidate))) {
                existingDefinitions.add(candidate);
            }
        }

        if (existingDefinitions.size() > 1) {
            throw new IOException("Refusing to save " + definitionId + ": multiple YAML files already define it: "
                    + existingDefinitions);
        }
        if (existingDefinitions.size() == 1) {
            return existingDefinitions.get(0);
        }

        boolean requestedExists = Files.exists(requestedTarget, LinkOption.NOFOLLOW_LINKS);
        if (!requestedExists) {
            return requestedTarget;
        }

        if (qualifiedTarget.equals(requestedTarget)) {
            throw new IOException("Refusing to overwrite " + requestedTarget
                    + ": it contains a different definition ID than " + definitionId);
        }
        if (Files.exists(qualifiedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite " + qualifiedTarget
                    + ": it contains a different definition ID than " + definitionId);
        }
        return qualifiedTarget;
    }

    private void addCandidate(Path root, Path realRoot, Path realDirectory,
                              Map<Path, Path> candidates, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IOException("Definition file is outside the definitions root: " + candidate);
        }
        candidates.putIfAbsent(normalizedCandidate, candidate);
        if (Files.exists(normalizedCandidate, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(normalizedCandidate)) {
            Path parent = normalizedCandidate.getParent().toRealPath();
            if (!parent.equals(realRoot) && !parent.startsWith(realDirectory)) {
                throw new IOException("Definition file is outside the flat or typed definition directory: " + candidate);
            }
        }
    }

    private static void deleteTemporaryFile(Path tmp, Throwable failure) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private NamespacedId readDefinitionId(Path file) throws IOException {
        JsonNode tree;
        try {
            tree = mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("Cannot safely replace " + file + " because its definition ID cannot be read", e);
        }
        return readDefinitionId(tree, file.toString());
    }

    private static String legacyFileNameFor(NamespacedId id) {
        return LEGACY_UNSAFE_FILENAME_CHARS.matcher(id.getPath()).replaceAll("_");
    }

    private NamespacedId readDefinitionId(JsonNode tree, String source) throws IOException {
        JsonNode idNode = tree == null ? null : tree.get("id");
        if (idNode == null || !idNode.isTextual()) {
            throw new IOException("Cannot safely replace " + source + ": expected a textual definition ID");
        }
        try {
            return NamespacedId.of(idNode.asText());
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot safely replace " + source + ": invalid definition ID", e);
        }
    }

    private static String qualifiedFileNameFor(NamespacedId id) {
        return id.getNamespace() + "@" + id.getPath().replace("/", "%2F");
    }

    static List<String> fileNameCandidatesFor(NamespacedId id) {
        return List.of(fileNameFor(id), legacyFileNameFor(id), qualifiedFileNameFor(id)).stream()
                .distinct()
                .toList();
    }

    /**
     * Returns a filesystem-safe and namespace-preserving definition filename.
     * Default-namespace single-segment IDs keep their legacy names; every other
     * ID includes an '@' namespace separator and percent-encodes path separators.
     */
    public static String fileNameFor(NamespacedId id) {
        if (id == null) {
            return "unnamed";
        }

        String namespace = id.getNamespace();
        String path = id.getPath();
        boolean canKeepLegacyName = NamespacedId.DEFAULT_NAMESPACE.equals(namespace)
                && !path.contains("/")
                && !WINDOWS_DEVICE_NAME.matcher(path).matches();
        if (canKeepLegacyName) {
            return path;
        }

        // '@' and '%' are not legal in a NamespacedId component, so this
        // encoding is injective across namespaces and path-segment boundaries.
        return qualifiedFileNameFor(id);
    }
}
