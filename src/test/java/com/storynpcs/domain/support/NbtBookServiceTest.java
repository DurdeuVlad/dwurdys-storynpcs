package com.storynpcs.domain.support;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** NBT book view/edit planner coverage (#148). */
class NbtBookServiceTest {

    @Test
    void buildViewFlattensNestedTagsToDottedPaths() {
        var tag = new CompoundTag();
        tag.putString("CustomName", "\"Guard\"");
        tag.putByte("NoGravity", (byte) 1);
        var pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(1.5));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(64.0));
        tag.put("Pos", pos);
        var inner = new CompoundTag();
        inner.putInt("health", 20);
        tag.put("Brain", inner);

        var entries = NbtBookService.buildView(tag);
        var paths = entries.stream().map(NbtBookService.NbtEntry::path).toList();
        assertThat(paths).contains("CustomName", "NoGravity", "Pos[0]", "Pos[1]", "Brain.health");
    }

    @Test
    void onlyAllowlistedTopLevelKeysAreEditable() {
        var tag = new CompoundTag();
        tag.putByte("NoGravity", (byte) 1);
        tag.putString("CustomName", "\"Guard\"");
        var inner = new CompoundTag();
        inner.putString("CustomName", "\"nested\"");
        tag.put("Nested", inner);
        tag.putInt("Health", 20);

        var entries = NbtBookService.buildView(tag);
        for (var entry : entries) {
            boolean expectedEditable = entry.path().equals("NoGravity")
                    || entry.path().equals("CustomName");
            assertThat(entry.editable())
                    .as("%s editable", entry.path())
                    .isEqualTo(expectedEditable);
        }
    }

    @Test
    void planEditAcceptsAllowlistedFlagsAndText() {
        assertThat(NbtBookService.planEdit("NoGravity", "true")).hasValueSatisfying(
                e -> assertThat(e.boolValue()).isTrue());
        assertThat(NbtBookService.planEdit("Invulnerable", "0")).hasValueSatisfying(
                e -> assertThat(e.boolValue()).isFalse());
        assertThat(NbtBookService.planEdit("CustomName", "  Gatekeeper  ")).hasValueSatisfying(
                e -> assertThat(e.textValue()).isEqualTo("Gatekeeper"));
    }

    @Test
    void planEditRejectsNonAllowlistedPathsAndBadValues() {
        assertThat(NbtBookService.planEdit("Health", "500")).isEmpty();
        assertThat(NbtBookService.planEdit("Brain.memories", "x")).isEmpty();
        assertThat(NbtBookService.planEdit("NoGravity", "maybe")).isEmpty();
        assertThat(NbtBookService.planEdit("CustomName", "x".repeat(300))).isEmpty();
        assertThat(NbtBookService.planEdit("Nested.CustomName", "x")).isEmpty();
    }

    @Test
    void describeEditReportsAllowlistOnRejection() {
        var result = NbtBookService.describeEdit("Health", "500");
        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).contains("read-only").contains("NoGravity");
    }

    @Test
    void entriesJsonRoundTrips() {
        var entries = java.util.List.of(
                new NbtBookService.NbtEntry("CustomName", "\"Guard\"", true),
                new NbtBookService.NbtEntry("Pos[0]", "1.5", false));
        var restored = NbtBookService.entriesFromJson(NbtBookService.entriesToJson(entries));
        assertThat(restored).isEqualTo(entries);
        assertThat(NbtBookService.entriesFromJson("not-json")).isEmpty();
        assertThat(NbtBookService.entriesFromJson(null)).isEmpty();
    }

    @Test
    void viewIsBoundedForDeepAndWideTags() {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (int i = 0; i < 500; i++) {
            list.add(StringTag.valueOf("x" + i));
        }
        tag.put("Big", list);
        var entries = NbtBookService.buildView(tag);
        assertThat(entries.size()).isLessThanOrEqualTo(512);
    }

    @Test
    void flagEditsAcceptSnbtByteLiteralsFromTheView() {
        // Rows display byte flags as "1b"/"0b"; applying them back must work.
        assertThat(NbtBookService.planEdit("NoGravity", "1b"))
                .map(NbtBookService.NbtEdit::boolValue).contains(true);
        assertThat(NbtBookService.planEdit("NoGravity", "0b"))
                .map(NbtBookService.NbtEdit::boolValue).contains(false);
    }

    @Test
    void boundedJsonTrimsToUtf8ByteBudget() {
        var entries = new java.util.ArrayList<NbtBookService.NbtEntry>();
        for (int i = 0; i < 400; i++) {
            entries.add(new NbtBookService.NbtEntry("k" + i, "éééé".repeat(40), false));
        }
        String json = NbtBookService.entriesToJsonBounded(entries, 8 * 1024);
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(8 * 1024);
        var restored = NbtBookService.entriesFromJson(json);
        assertThat(restored).isNotEmpty();
        assertThat(restored.get(restored.size() - 1).path()).isEqualTo("…");
    }
}
