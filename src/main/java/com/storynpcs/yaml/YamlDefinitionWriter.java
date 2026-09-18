package com.storynpcs.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.dialogue.DialogueGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes domain definitions back to YAML on disk.
 * Uses the same atomic .tmp -> rename discipline as the persistence repositories,
 * so a crash mid-write never leaves a truncated definition file.
 */
public class YamlDefinitionWriter {

    private final ObjectMapper mapper;

    public YamlDefinitionWriter() {
        this.mapper = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }

    /** Writes a dialogue to {@code <definitionsRoot>/dialogues/<path>.yaml}. */
    public Path writeDialogue(Path definitionsRoot, DialogueGraph graph) throws IOException {
        return writeDefinition(definitionsRoot, "dialogues", fileNameFor(graph.getId()), graph);
    }

    /** Atomically writes {@code value} to {@code <root>/<subdir>/<baseName>.yaml}. */
    public Path writeDefinition(Path root, String subdir, String baseName, Object value) throws IOException {
        Path dir = root.resolve(subdir);
        Files.createDirectories(dir);
        Path target = dir.resolve(baseName + ".yaml");
        Path tmp = dir.resolve(baseName + ".yaml.tmp");
        mapper.writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Sanitizes a namespaced id's path component into a safe file base name. */
    public static String fileNameFor(NamespacedId id) {
        String path = id != null ? id.getPath() : "unnamed";
        return path.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
