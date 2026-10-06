package com.storynpcs.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storynpcs.creator.world.ScriptedHookBinding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Durable world-tool activation bindings (P8-3): which positions carry an
 * activated tool and its inert hook set. One record per activated position,
 * keyed {@code dim|x,y,z}; writes are atomic via {@link IndexedRecordStore}.
 */
public final class WorldToolBindingStore {

    /** A position's bound tool + hook payload. */
    public record Binding(String toolId, List<ScriptedHookBinding> hooks) {
        public Binding {
            hooks = hooks == null ? List.of() : List.copyOf(hooks);
        }
    }

    public record BindingEntry(String toolId, String dimensionId, long pos,
                               List<ScriptedHookBinding> hooks) {}

    private final IndexedRecordStore store;

    public WorldToolBindingStore(Path directory, ObjectMapper mapper) {
        this.store = new IndexedRecordStore(directory, mapper);
    }

    public void open() throws IOException {
        store.open();
    }

    public static String key(String dimensionId, long pos) {
        return dimensionId + "|" + pos;
    }

    public void save(String dimensionId, long pos, String toolId,
                     List<ScriptedHookBinding> hooks) throws IOException {
        store.write(key(dimensionId, pos), new Binding(toolId, hooks));
    }

    public Optional<Binding> load(String dimensionId, long pos) throws IOException {
        return store.read(key(dimensionId, pos), Binding.class);
    }

    public boolean delete(String dimensionId, long pos) throws IOException {
        return store.delete(key(dimensionId, pos));
    }

    /** All bindings — bounded listing for lifecycle/unload cleanup. */
    public List<BindingEntry> list() throws IOException {
        var entries = new java.util.ArrayList<BindingEntry>();
        for (String id : store.listIds()) {
            var binding = store.read(id, Binding.class);
            binding.ifPresent(b -> {
                int sep = id.lastIndexOf('|');
                entries.add(new BindingEntry(b.toolId(), id.substring(0, sep),
                        Long.parseLong(id.substring(sep + 1)), b.hooks()));
            });
        }
        return entries;
    }
}
