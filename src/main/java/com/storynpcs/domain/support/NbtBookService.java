package com.storynpcs.domain.support;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NBT book backend (issue #148): pure-logic view builder and edit planner for
 * the creator NBT-inspection tool.
 *
 * <p>The target {@code ItemNbtBook} reads and writes <em>arbitrary</em> entity
 * NBT. That conflicts with the YAML-first / canonical-operations rules
 * (ADR-006): definition data on a {@code StoryNpcEntity} must only change
 * through {@code StoryNpcsApplicationService}. The StoryNPCs scope is
 * therefore deliberately narrower — a full read view plus an allowlist of
 * ephemeral entity-state keys applied through typed entity setters:
 *
 * <ul>
 *   <li>{@code CustomName} — display name (string)</li>
 *   <li>{@code NoGravity} — gravity toggle (boolean)</li>
 *   <li>{@code Invulnerable} — invulnerability flag (boolean)</li>
 *   <li>{@code Silent} — silence flag (boolean)</li>
 *   <li>{@code Glowing} — outline glow flag (boolean)</li>
 * </ul>
 *
 * <p>Everything else — including all definition-backed NPC fields — is
 * read-only. {@link #planEdit} is the fail-closed gate: it returns a typed
 * {@link NbtEdit} only for allowlisted paths with coercible values; the
 * adapter applies the plan to the live entity.
 */
public final class NbtBookService {

    /** One flattened NBT view line. */
    public record NbtEntry(String path, String value, boolean editable) {}

    /** A validated, typed edit plan for one allowlisted key. */
    public record NbtEdit(String path, Kind kind, String textValue, boolean boolValue) {
        public enum Kind { TEXT, FLAG }
    }

    /** Outcome of a requested edit. */
    public record EditResult(boolean accepted, String message) {
        public static EditResult accepted(String message) {
            return new EditResult(true, message);
        }
        public static EditResult rejected(String message) {
            return new EditResult(false, message);
        }
    }

    /** Allowlisted top-level keys and their edit kind — everything else is read-only. */
    private static final Map<String, NbtEdit.Kind> EDITABLE_KEYS = new LinkedHashMap<>() {{
        put("CustomName", NbtEdit.Kind.TEXT);
        put("NoGravity", NbtEdit.Kind.FLAG);
        put("Invulnerable", NbtEdit.Kind.FLAG);
        put("Silent", NbtEdit.Kind.FLAG);
        put("Glowing", NbtEdit.Kind.FLAG);
    }};

    private static final int MAX_ENTRIES = 512;
    private static final int MAX_VALUE_CHARS = 512;
    private static final int MAX_TEXT_EDIT_CHARS = 256;

    /** Flattened, human-readable NBT view for one entity's saved tag. */
    public static List<NbtEntry> buildView(CompoundTag entityTag) {
        List<NbtEntry> entries = new ArrayList<>();
        if (entityTag == null) {
            return entries;
        }
        flatten("", entityTag, entries);
        return entries;
    }

    public static boolean isEditable(String path) {
        return EDITABLE_KEYS.containsKey(path);
    }

    /**
     * Validates an edit request into an {@link NbtEdit}; empty when the path
     * is not allowlisted or the value cannot be coerced.
     */
    public static Optional<NbtEdit> planEdit(String path, String rawValue) {
        NbtEdit.Kind kind = EDITABLE_KEYS.get(path);
        if (kind == null || rawValue == null) {
            return Optional.empty();
        }
        return switch (kind) {
            case TEXT -> {
                String trimmed = rawValue.strip();
                if (trimmed.length() > MAX_TEXT_EDIT_CHARS) {
                    yield Optional.empty();
                }
                yield Optional.of(new NbtEdit(path, kind, trimmed, false));
            }
            case FLAG -> switch (rawValue.strip().toLowerCase()) {
                case "true", "1", "1b", "yes", "on" ->
                        Optional.of(new NbtEdit(path, kind, "1", true));
                case "false", "0", "0b", "no", "off" ->
                        Optional.of(new NbtEdit(path, kind, "0", false));
                default -> Optional.empty();
            };
        };
    }

    /**
     * Applies one validated edit to the target via typed setters — never
     * {@code Entity#load}, so definition-backed fields cannot be reached.
     */
    public static void applyEdit(net.minecraft.world.entity.Entity target, NbtEdit edit) {
        switch (edit.path()) {
            case "CustomName" ->
                    target.setCustomName(edit.textValue().isEmpty() ? null
                            : net.minecraft.network.chat.Component.literal(edit.textValue()));
            case "NoGravity" -> target.setNoGravity(edit.boolValue());
            case "Invulnerable" -> target.setInvulnerable(edit.boolValue());
            case "Silent" -> target.setSilent(edit.boolValue());
            case "Glowing" -> target.setGlowingTag(edit.boolValue());
            default -> { } // planEdit only emits allowlisted paths
        }
    }

    /** Human-facing rejection/acceptance message for a raw edit request. */
    public static EditResult describeEdit(String path, String rawValue) {
        if (!EDITABLE_KEYS.containsKey(path)) {
            return EditResult.rejected("'" + path + "' is read-only — only these keys are editable: "
                    + String.join(", ", EDITABLE_KEYS.keySet()));
        }
        return planEdit(path, rawValue)
                .map(edit -> EditResult.accepted(path + " <- " + rawValue.strip()))
                .orElse(EditResult.rejected("Cannot parse value for " + path));
    }

    private static void flatten(String prefix, CompoundTag tag, List<NbtEntry> out) {
        if (out.size() >= MAX_ENTRIES) {
            return;
        }
        for (String key : tag.getAllKeys()) {
            if (out.size() >= MAX_ENTRIES) {
                return;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                flatten(path, compound, out);
            } else if (child instanceof ListTag list) {
                flattenList(path, list, out);
            } else {
                out.add(new NbtEntry(path, displayValue(child), isEditable(path)));
            }
        }
    }

    private static void flattenList(String path, ListTag list, List<NbtEntry> out) {
        int limit = Math.min(list.size(), 64);
        for (int i = 0; i < limit && out.size() < MAX_ENTRIES; i++) {
            String childPath = path + "[" + i + "]";
            Tag child = list.get(i);
            if (child instanceof CompoundTag compound) {
                flatten(childPath, compound, out);
            } else {
                out.add(new NbtEntry(childPath, displayValue(child), false));
            }
        }
        if (list.size() > limit) {
            out.add(new NbtEntry(path, "… " + (list.size() - limit) + " more entries", false));
        }
    }

    private static String displayValue(Tag tag) {
        String raw = tag.getAsString();
        return raw.length() > MAX_VALUE_CHARS ? raw.substring(0, MAX_VALUE_CHARS) + "…" : raw;
    }

    // ---- wire serde (JSON envelope carried by the versioned payloads) ----

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static String entriesToJson(List<NbtEntry> entries) {
        try {
            return MAPPER.writeValueAsString(entries != null ? entries : List.of());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize NBT book entries", e);
        }
    }

    /**
     * Serializes entries bounded to {@code maxBytes} of UTF-8, dropping tail
     * rows and appending a truncation marker when the full view would not fit
     * the wire budget. Byte-measured, not char-measured, so multibyte NBT
     * values cannot overflow the payload codec.
     */
    public static String entriesToJsonBounded(List<NbtEntry> entries, int maxBytes) {
        List<NbtEntry> safe = entries != null ? entries : List.of();
        String json = entriesToJson(safe);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            return json;
        }
        List<NbtEntry> trimmed = new ArrayList<>(safe);
        while (!trimmed.isEmpty()) {
            trimmed.remove(trimmed.size() - 1);
            List<NbtEntry> attempt = new ArrayList<>(trimmed);
            attempt.add(new NbtEntry("…", "view truncated to fit payload", false));
            json = entriesToJson(attempt);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
                return json;
            }
        }
        return entriesToJson(List.of(new NbtEntry("…", "view truncated to fit payload", false)));
    }

    public static List<NbtEntry> entriesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<NbtEntry>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private NbtBookService() {}
}
