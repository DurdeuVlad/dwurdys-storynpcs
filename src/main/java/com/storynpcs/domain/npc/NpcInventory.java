package com.storynpcs.domain.npc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.storynpcs.domain.common.NamespacedId;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Target-equivalent NPC inventory: 4 armor slots, 3 weapon positions, and 21
 * API-addressable drop entries (indices 0–20) whose first 9 are the visible GUI
 * slots. Mutations are explicit and rejection-safe — an invalid slot, an empty
 * stack, or an out-of-range drop index fails without touching stored items.
 */
public class NpcInventory {

    public static final int DROP_SLOTS = 21;
    public static final int VISIBLE_DROP_SLOTS = 9;

    /** Equipment positions: 4 armor + 3 weapon slots. */
    public enum ItemSlot {
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS,
        RIGHT_HAND,
        LEFT_HAND,
        PROJECTILE
    }

    public enum LootMode {
        NORMAL,
        NOTHING,
        NPC_ONLY
    }

    public enum EquipOutcome {
        EQUIPPED,
        REPLACED,
        REJECTED_EMPTY,
        REJECTED_INVALID_SLOT
    }

    /** Result of an equip attempt; {@code previous} holds the displaced stack. */
    public record EquipResult(EquipOutcome outcome, NpcItemStack previous) {
        public boolean applied() { return outcome == EquipOutcome.EQUIPPED || outcome == EquipOutcome.REPLACED; }
    }

    public enum DropOutcome {
        SET,
        CLEARED,
        REJECTED_INVALID_INDEX,
        REJECTED_INVALID_CHANCE,
        REJECTED_EMPTY
    }

    public record DropResult(DropOutcome outcome, int index) {
        public boolean applied() { return outcome == DropOutcome.SET || outcome == DropOutcome.CLEARED; }
    }

    /** One drop entry: the item plus its independent drop probability. */
    public static class DropEntry {
        @JsonProperty
        private NpcItemStack item;
        @JsonProperty
        private int chancePercent = 100;

        public DropEntry() {}

        public DropEntry(NpcItemStack item, int chancePercent) {
            setItem(item);
            setChancePercent(chancePercent);
        }

        public NpcItemStack getItem() { return item; }
        public void setItem(NpcItemStack item) { this.item = item; }

        public int getChancePercent() { return chancePercent; }
        public void setChancePercent(int chancePercent) {
            if (chancePercent < 0 || chancePercent > 100) {
                throw new IllegalArgumentException("chancePercent must be between 0 and 100");
            }
            this.chancePercent = chancePercent;
        }
    }

    @JsonProperty
    private Map<ItemSlot, NpcItemStack> equipment = new EnumMap<>(ItemSlot.class);

    @JsonProperty
    private List<DropEntry> drops = freshDropList();

    @JsonProperty
    private LootMode lootMode = LootMode.NORMAL;

    public NpcInventory() {}

    private static List<DropEntry> freshDropList() {
        List<DropEntry> list = new ArrayList<>(DROP_SLOTS);
        for (int i = 0; i < DROP_SLOTS; i++) list.add(new DropEntry());
        return list;
    }

    public Map<ItemSlot, NpcItemStack> getEquipment() {
        return java.util.Collections.unmodifiableMap(equipment);
    }
    public void setEquipment(Map<ItemSlot, NpcItemStack> equipment) {
        this.equipment = new EnumMap<>(ItemSlot.class);
        if (equipment == null) return;
        for (Map.Entry<ItemSlot, NpcItemStack> entry : equipment.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("equipment entries cannot be null");
            }
            this.equipment.put(entry.getKey(), entry.getValue());
        }
    }

    public List<DropEntry> getDrops() { return drops; }
    public void setDrops(List<DropEntry> drops) {
        if (drops != null && drops.size() > DROP_SLOTS) {
            throw new IllegalArgumentException("drops cannot exceed " + DROP_SLOTS + " entries");
        }
        List<DropEntry> staged = freshDropList();
        if (drops != null) {
            for (int i = 0; i < drops.size(); i++) {
                DropEntry entry = drops.get(i);
                if (entry != null) staged.set(i, entry);
            }
        }
        this.drops = staged;
    }

    public LootMode getLootMode() { return lootMode; }
    public void setLootMode(LootMode lootMode) {
        this.lootMode = lootMode != null ? lootMode : LootMode.NORMAL;
    }

    /** Equips a stack into a slot, returning the displaced stack when any. */
    public EquipResult equip(ItemSlot slot, NpcItemStack stack) {
        if (slot == null) return new EquipResult(EquipOutcome.REJECTED_INVALID_SLOT, null);
        if (stack == null) return new EquipResult(EquipOutcome.REJECTED_EMPTY, null);
        NpcItemStack previous = equipment.put(slot, stack);
        return new EquipResult(
                previous != null ? EquipOutcome.REPLACED : EquipOutcome.EQUIPPED, previous);
    }

    /** Removes and returns the stack in a slot, if occupied. */
    public Optional<NpcItemStack> unequip(ItemSlot slot) {
        if (slot == null) return Optional.empty();
        return Optional.ofNullable(equipment.remove(slot));
    }

    /** Sets a drop slot; index must be within 0..20 and chance within 0..100. */
    public DropResult setDrop(int index, NpcItemStack item, int chancePercent) {
        if (index < 0 || index >= DROP_SLOTS) {
            return new DropResult(DropOutcome.REJECTED_INVALID_INDEX, index);
        }
        if (item == null) return new DropResult(DropOutcome.REJECTED_EMPTY, index);
        if (chancePercent < 0 || chancePercent > 100) {
            return new DropResult(DropOutcome.REJECTED_INVALID_CHANCE, index);
        }
        drops.set(index, new DropEntry(item, chancePercent));
        return new DropResult(DropOutcome.SET, index);
    }

    public DropResult clearDrop(int index) {
        if (index < 0 || index >= DROP_SLOTS) {
            return new DropResult(DropOutcome.REJECTED_INVALID_INDEX, index);
        }
        drops.set(index, new DropEntry());
        return new DropResult(DropOutcome.CLEARED, index);
    }

    /** Item ids across equipment and occupied drops — the legacy flat inventory view. */
    public List<String> legacyItemIds() {
        List<String> ids = new ArrayList<>();
        for (NpcItemStack stack : equipment.values()) {
            if (stack != null) ids.add(stack.itemId().toString());
        }
        for (DropEntry drop : drops) {
            if (drop != null && drop.getItem() != null) ids.add(drop.getItem().itemId().toString());
        }
        return ids;
    }

    /**
     * Dual-shape deserialization: the structured object form round-trips, and the
     * legacy {@code inventory: ["namespace:item", ...]} string list migrates onto
     * the visible drop slots at 100% chance — nothing is silently dropped.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static NpcInventory fromJsonNode(JsonNode node) {
        NpcInventory inventory = new NpcInventory();
        if (node == null || node.isNull()) return inventory;
        if (node.isArray()) {
            if (node.size() > DROP_SLOTS) {
                throw new IllegalArgumentException(
                        "legacy inventory exceeds " + DROP_SLOTS + " supported drop slots");
            }
            int slot = 0;
            for (JsonNode element : node) {
                if (!element.isTextual()) {
                    throw new IllegalArgumentException("legacy inventory entries must be item-id strings");
                }
                inventory.setDrop(slot++, NpcItemStack.single(NamespacedId.of(element.asText())), 100);
            }
            return inventory;
        }
        if (node.isObject()) {
            // Strict boundary (issue #55 / P2-1): unknown keys, unknown slot
            // names, malformed stacks, and out-of-range values are rejected
            // with field-path diagnostics instead of being silently dropped.
            for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
                String key = it.next();
                if (!key.equals("equipment") && !key.equals("drops") && !key.equals("lootMode")) {
                    throw new IllegalArgumentException("inventory." + key + ": unknown field");
                }
            }
            JsonNode equipment = node.get("equipment");
            if (equipment != null) {
                if (!equipment.isObject()) {
                    throw new IllegalArgumentException("inventory.equipment must be a slot-name mapping");
                }
                for (Iterator<Map.Entry<String, JsonNode>> it = equipment.fields(); it.hasNext();) {
                    Map.Entry<String, JsonNode> field = it.next();
                    ItemSlot slot;
                    try {
                        slot = ItemSlot.valueOf(field.getKey().trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "inventory.equipment." + field.getKey() + ": unknown equipment slot");
                    }
                    inventory.equip(slot, requireItem(field.getValue(),
                            "inventory.equipment." + field.getKey()));
                }
            }
            JsonNode drops = node.get("drops");
            if (drops != null) {
                if (!drops.isArray()) {
                    throw new IllegalArgumentException("inventory.drops must be an array");
                }
                if (drops.size() > DROP_SLOTS) {
                    throw new IllegalArgumentException("drops exceeds " + DROP_SLOTS + " supported slots");
                }
                int index = 0;
                for (JsonNode drop : drops) {
                    JsonNode itemNode = drop.get("item");
                    if (itemNode == null || itemNode.isNull()) {
                        index++; // serialized empty slot — a gap, not dropped content
                        continue;
                    }
                    NpcItemStack item = requireItem(itemNode, "inventory.drops[" + index + "].item");
                    int chance = drop.has("chancePercent") ? drop.get("chancePercent").asInt(100) : 100;
                    if (chance < 0 || chance > 100) {
                        throw new IllegalArgumentException(
                                "inventory.drops[" + index + "].chancePercent must be 0..100, got " + chance);
                    }
                    inventory.setDrop(index, item, chance);
                    index++;
                }
            }
            JsonNode lootMode = node.get("lootMode");
            if (lootMode != null) {
                if (!lootMode.isTextual()) {
                    throw new IllegalArgumentException("inventory.lootMode must be a string");
                }
                try {
                    inventory.setLootMode(LootMode.valueOf(lootMode.asText().trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "inventory.lootMode: unknown value '" + lootMode.asText() + "'");
                }
            }
            return inventory;
        }
        throw new IllegalArgumentException("inventory must be an object or legacy item-id array");
    }

    private static NpcItemStack requireItem(JsonNode node, String fieldPath) {
        NpcItemStack item = itemFromNode(node);
        if (item == null) {
            throw new IllegalArgumentException(
                    fieldPath + ": malformed item stack (requires textual itemId and count 1.."
                            + NpcItemStack.MAX_COUNT + ")");
        }
        return item;
    }

    private static NpcItemStack itemFromNode(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return null;
        JsonNode id = node.get("itemId");
        if (id == null || !id.isTextual() || id.asText().isBlank()) return null;
        int count = node.has("count") ? node.get("count").asInt(1) : 1;
        String components = node.has("components") ? node.get("components").asText("") : "";
        try {
            return new NpcItemStack(NamespacedId.of(id.asText()), count, components);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

}
