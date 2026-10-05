package com.storynpcs.service;

import com.storynpcs.StoryNpcs;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.namegen.MarkovNameGenerator;
import com.storynpcs.domain.namegen.NameDictionary;
import com.storynpcs.domain.namegen.NameDictionaryLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Name-dictionary catalog + seeded generation (issue #123). Bundled YAML
 * dictionaries ship at {@code data/storynpcs/namegen/}; creator dictionaries
 * at {@code config/storynpcs/namegen/} win on id collision — the same
 * bundled-plus-creator contract as {@code SchematicStore}. Bad creator
 * documents warn-and-skip; a missing catalog fails closed with an empty
 * suggestion.
 */
public class NameGenerationService {

    private static final String BUNDLED_DIR = "namegen";
    private static final String BUNDLED_RESOURCE_DIR = "data/storynpcs/namegen";

    private final Map<NamespacedId, NameDictionary> byId;

    private NameGenerationService(Map<NamespacedId, NameDictionary> byId) {
        this.byId = byId;
    }

    /** Bundled dictionaries plus creator overrides; creator file wins on id. */
    public static NameGenerationService load(MinecraftServer server) {
        return load(server, creatorDirOrNull());
    }

    static NameGenerationService load(MinecraftServer server, Path creatorDir) {
        Map<NamespacedId, NameDictionary> byId = new LinkedHashMap<>();
        if (server != null) {
            server.getResourceManager()
                    .listResources(BUNDLED_RESOURCE_DIR,
                            rl -> rl.getPath().endsWith(".yaml"))
                    .forEach((rl, resource) -> {
                        try (InputStream in = resource.open()) {
                            var dict = NameDictionaryLoader.parse(
                                    rl.toString(),
                                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
                            byId.put(dict.namespacedId(), dict);
                        } catch (IOException | IllegalArgumentException e) {
                            StoryNpcs.LOGGER.warn("Skipping bundled name dictionary {}: {}",
                                    rl, e.getMessage());
                        }
                    });
        }
        if (creatorDir != null && Files.isDirectory(creatorDir)) {
            try (var stream = Files.list(creatorDir)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .toList()) {
                    try {
                        var dict = NameDictionaryLoader.parse(
                                file.getFileName().toString(),
                                Files.readString(file, StandardCharsets.UTF_8));
                        byId.put(dict.namespacedId(), dict); // creator wins on collision
                    } catch (IOException | IllegalArgumentException e) {
                        StoryNpcs.LOGGER.warn("Skipping creator name dictionary {}: {}",
                                file.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                StoryNpcs.LOGGER.warn("Could not scan creator name dictionary dir {}: {}",
                        creatorDir, e.getMessage());
            }
        }
        return new NameGenerationService(Collections.unmodifiableMap(byId));
    }

    /** {@code config/storynpcs/namegen}; null when the config root is absent (tests). */
    static Path creatorDirOrNull() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get();
            return dir != null ? dir.resolve("storynpcs").resolve(BUNDLED_DIR) : null;
        } catch (Throwable t) {
            return null; // headless unit tests have no FML environment
        }
    }

    /** Known dictionary ids — for command suggestions and diagnostics. */
    public Set<NamespacedId> cultures() {
        return byId.keySet();
    }

    /** Deterministic suggestion: same dictionary + seed, same name. */
    public Optional<String> suggest(NamespacedId dictionaryId, long seed) {
        var dict = byId.get(dictionaryId);
        if (dict == null) {
            return Optional.empty();
        }
        return MarkovNameGenerator.generate(dict, seed);
    }
}
