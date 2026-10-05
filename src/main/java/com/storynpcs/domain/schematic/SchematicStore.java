package com.storynpcs.domain.schematic;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Schematic lookup (issue #149, ADR-006): bundled read-only assets ship in the
 * mod jar at {@code data/storynpcs/schematics/} (resource-manager readable,
 * datapack-overridable); creator-added schematics are plain files under
 * {@code config/storynpcs/schematics/}. Names are strict {@code [a-z0-9_./-]}
 * ids — path traversal and extension spoofing are rejected before any IO.
 */
public final class SchematicStore {

    public static final String BUNDLED_DIR = "schematics";
    public static final String NAMESPACE = "storynpcs";
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_./-]+");
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** Outcome of a load: either the parsed schematic or a human-facing error. */
    public record LoadResult(Optional<Schematic> schematic, String error) {
        public static LoadResult ok(Schematic s) { return new LoadResult(Optional.of(s), null); }
        public static LoadResult error(String e) { return new LoadResult(Optional.empty(), e); }
    }

    /** Directory for creator-added schematic files (created lazily). */
    public static Path creatorDir() {
        return FMLPaths.CONFIGDIR.get().resolve("storynpcs").resolve(BUNDLED_DIR);
    }

    /**
     * Creator dir tolerant of a missing FML runtime (unit tests bootstrap no
     * mod loader) — null means creator-added files are unavailable.
     */
    static Path creatorDirOrNull() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get();
            return dir == null ? null : dir.resolve("storynpcs").resolve(BUNDLED_DIR);
        } catch (Throwable t) {
            return null;
        }
    }

    /** All loadable schematic names: bundled first, then creator files. */
    public static List<String> list(MinecraftServer server) {
        return list(server, creatorDirOrNull());
    }

    /** Test-friendly overload with an explicit creator directory. */
    static List<String> list(MinecraftServer server, Path creatorDir) {
        TreeSet<String> names = new TreeSet<>();
        if (server != null) {
            server.getResourceManager()
                    .listResources(BUNDLED_DIR,
                            id -> id.getPath().endsWith(".schem") || id.getPath().endsWith(".schematic"))
                    .keySet()
                    .forEach(id -> {
                        if (id.getNamespace().equals(NAMESPACE)) {
                            String name = stripExt(id.getPath().substring(BUNDLED_DIR.length() + 1));
                            if (isLoadableName(name)) {
                                names.add(name);
                            }
                        }
                    });
        }
        Path dir = creatorDir;
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".schem") || n.endsWith(".schematic"))
                        .map(SchematicStore::stripExt)
                        .filter(SchematicStore::isLoadableName)
                        .forEach(names::add);
            } catch (IOException ignored) {
                // An unreadable creator dir degrades to bundled-only.
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * Loads a schematic by name: creator file wins over a bundled asset of the
     * same name (creators can shadow ships without touching the jar).
     */
    public static LoadResult load(MinecraftServer server, String name) {
        return load(server, name, creatorDirOrNull());
    }

    /** Test-friendly overload with an explicit creator directory. */
    static LoadResult load(MinecraftServer server, String name, Path creatorDir) {
        // VALID_NAME permits dots for names like "tier_house1.v2" — the segment
        // check rejects traversal before the normalized-path guard below ever runs.
        if (!isLoadableName(name)) {
            return LoadResult.error("invalid schematic name '" + name + "'");
        }
        // 1. Creator file.
        for (String ext : new String[]{".schem", ".schematic"}) {
            if (creatorDir == null) {
                break;
            }
            Path file = creatorDir.resolve(name + ext).normalize();
            if (!file.startsWith(creatorDir) || !Files.isRegularFile(file)) {
                continue;
            }
            // Bounded read — the same readNBytes cap as the bundled path, so a
            // file that grows past the limit after resolve() cannot balloon
            // the buffer before the compressed-NBT accounter runs.
            try (var in = Files.newInputStream(file)) {
                byte[] bytes = in.readNBytes((int) (MAX_FILE_BYTES + 1));
                if (bytes.length > MAX_FILE_BYTES) {
                    return LoadResult.error("'" + name + ext + "' exceeds 32 MiB file bound");
                }
                return LoadResult.ok(SchematicReader.read(name + ext, bytes));
            } catch (SchematicParseException e) {
                return LoadResult.error(e.getMessage());
            } catch (IOException e) {
                return LoadResult.error(name + ext + ": unreadable (" + e.getMessage() + ")");
            }
        }
        // 2. Bundled resource.
        if (server != null) {
            for (String ext : new String[]{".schem", ".schematic"}) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                        NAMESPACE, BUNDLED_DIR + "/" + name + ext);
                var resource = server.getResourceManager().getResource(id);
                if (resource.isEmpty()) {
                    continue;
                }
                try (var in = resource.get().open()) {
                    // Bounded read — a datapack can ship an oversized file and
                    // readAllBytes would buffer it all before the accounter runs.
                    byte[] bytes = in.readNBytes((int) (MAX_FILE_BYTES + 1));
                    if (bytes.length > MAX_FILE_BYTES) {
                        return LoadResult.error("'" + name + ext
                                + "' exceeds 32 MiB file bound");
                    }
                    return LoadResult.ok(SchematicReader.read(name + ext, bytes));
                } catch (SchematicParseException e) {
                    return LoadResult.error(e.getMessage());
                } catch (IOException e) {
                    return LoadResult.error(name + ext + ": unreadable (" + e.getMessage() + ")");
                }
            }
        }
        return LoadResult.error("unknown schematic '" + name + "'");
    }

    /** Single name-validity rule shared by {@link #list} and {@link #load}. */
    private static boolean isLoadableName(String name) {
        return name != null && VALID_NAME.matcher(name).matches()
                && !name.contains("..") && !name.contains("\\") && !name.startsWith("/");
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private SchematicStore() {}
}
