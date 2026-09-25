package com.storynpcs.domain.role.banker;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

public class BankVault {

    public static class VaultItem {
        @JsonProperty
        private int slot;

        @JsonProperty
        private String itemId;

        @JsonProperty
        private int count;

        @JsonProperty
        private String tag;

        public VaultItem() {}

        public VaultItem(int slot, String itemId, int count) {
            this(slot, itemId, count, null);
        }

        public VaultItem(int slot, String itemId, int count, String tag) {
            this.slot = slot;
            this.itemId = itemId != null ? itemId : "";
            this.count = count;
            this.tag = tag;
        }

        public int getSlot() { return slot; }
        public void setSlot(int slot) { this.slot = slot; }

        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
    }

    /**
     * Durable proof that a multi-system operation was included in this vault
     * file. The operation journal owns lifecycle state; the vault marker proves
     * that the bank-side effect reached the same durable commit boundary.
     */
    public static class OperationMarker {
        @JsonProperty
        private UUID operationId;

        @JsonProperty
        private String operationType;

        @JsonProperty
        private String intent;

        @JsonProperty
        private boolean inventoryApplied;

        public OperationMarker() {}

        public OperationMarker(UUID operationId, String operationType, String intent) {
            this(operationId, operationType, intent, false);
        }

        public OperationMarker(UUID operationId, String operationType, String intent,
                               boolean inventoryApplied) {
            this.operationId = Objects.requireNonNull(operationId, "operationId");
            this.operationType = operationType != null ? operationType : "";
            this.intent = intent;
            this.inventoryApplied = inventoryApplied;
        }

        public UUID getOperationId() { return operationId; }
        public void setOperationId(UUID operationId) { this.operationId = operationId; }

        public String getOperationType() { return operationType; }
        public void setOperationType(String operationType) { this.operationType = operationType; }

        public String getIntent() { return intent; }
        public void setIntent(String intent) { this.intent = intent; }

        public boolean isInventoryApplied() { return inventoryApplied; }
        public void setInventoryApplied(boolean inventoryApplied) { this.inventoryApplied = inventoryApplied; }
    }

    @JsonProperty(required = true)
    private UUID playerUuid;

    @JsonProperty
    private int unlockedTabs = 1;

    @JsonProperty
    private Map<Integer, List<VaultItem>> tabs = new HashMap<>();

    @JsonProperty
    private Map<UUID, OperationMarker> operationMarkers = new HashMap<>();

    /** Monotonic durable version of the complete vault record. */
    @JsonProperty
    private long revision;

    public BankVault() {}

    public BankVault(UUID playerUuid) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.tabs.put(0, new ArrayList<>());
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }

    public int getUnlockedTabs() { return unlockedTabs; }

    /**
     * Rejects a tab count outside [0, {@link BankerRole#MAX_TABS}] (issue #76:
     * "tab count cannot exceed six"). This is deliberate defense-in-depth
     * independent of {@code BankerRole.maxTabs}'s own bound: a vault must
     * never accept an unlocked-tab count the target spec forbids regardless of
     * how the value arrived (network payload, YAML/JSON deserialization, a
     * future admin API), not only when the unlock path itself enforces it.
     */
    public void setUnlockedTabs(int unlockedTabs) {
        if (unlockedTabs < 0 || unlockedTabs > BankerRole.MAX_TABS) {
            throw new IllegalArgumentException(
                    "unlockedTabs must be between 0 and " + BankerRole.MAX_TABS + " (was " + unlockedTabs + ")");
        }
        this.unlockedTabs = unlockedTabs;
    }

    public synchronized long getRevision() { return revision; }

    public synchronized void setRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
        this.revision = revision;
    }

    /** Advances the durable version for one committed repository mutation. */
    public synchronized void advanceRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("bank vault revision exhausted");
        }
        revision++;
    }

    public synchronized Map<Integer, List<VaultItem>> getTabs() { return tabs; }
    public synchronized void setTabs(Map<Integer, List<VaultItem>> tabs) {
        this.tabs = tabs != null ? tabs : new HashMap<>();
    }

    /**
     * Creates a detached copy suitable for a transaction rollback. The copy
     * includes every mutable item and list so a failed durable write cannot
     * leave the cached vault partially changed.
     */
    public synchronized BankVault copy() {
        BankVault copy = new BankVault();
        copy.playerUuid = playerUuid;
        copy.unlockedTabs = unlockedTabs;
        copy.revision = revision;
        copy.tabs = deepCopyTabs(tabs);
        copy.operationMarkers = deepCopyOperationMarkers(operationMarkers);
        return copy;
    }

    /** Restores all mutable state from a detached snapshot. */
    public synchronized void restoreFrom(BankVault snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.playerUuid = snapshot.playerUuid;
        this.unlockedTabs = snapshot.unlockedTabs;
        this.revision = snapshot.revision;
        this.tabs = deepCopyTabs(snapshot.tabs);
        this.operationMarkers = deepCopyOperationMarkers(snapshot.operationMarkers);
    }

    private static Map<Integer, List<VaultItem>> deepCopyTabs(Map<Integer, List<VaultItem>> source) {
        Map<Integer, List<VaultItem>> copy = new HashMap<>();
        if (source == null) return copy;
        for (Map.Entry<Integer, List<VaultItem>> entry : source.entrySet()) {
            List<VaultItem> items = new ArrayList<>();
            if (entry.getValue() != null) {
                for (VaultItem item : entry.getValue()) {
                    if (item == null) continue;
                    items.add(new VaultItem(item.slot, item.itemId, item.count, item.tag));
                }
            }
            copy.put(entry.getKey(), items);
        }
        return copy;
    }

    private static Map<UUID, OperationMarker> deepCopyOperationMarkers(
            Map<UUID, OperationMarker> source) {
        Map<UUID, OperationMarker> copy = new HashMap<>();
        if (source == null) return copy;
        for (Map.Entry<UUID, OperationMarker> entry : source.entrySet()) {
            OperationMarker marker = entry.getValue();
            if (marker == null) continue;
            copy.put(entry.getKey(), new OperationMarker(
                    marker.getOperationId(), marker.getOperationType(), marker.getIntent(),
                    marker.isInventoryApplied()));
        }
        return copy;
    }

    /** Returns a detached marker, so callers cannot mutate cached vault state outside a transaction. */
    public synchronized OperationMarker getOperationMarker(UUID operationId) {
        OperationMarker marker = operationMarkers.get(operationId);
        return marker == null ? null
                : new OperationMarker(marker.getOperationId(), marker.getOperationType(), marker.getIntent(),
                        marker.isInventoryApplied());
    }

    /** Adds or confirms a durable operation marker inside the vault transaction. */
    public synchronized boolean markOperation(UUID operationId, String operationType, String intent) {
        if (operationId == null || operationType == null || operationType.isBlank()
                || intent == null || intent.isBlank() || intent.length() > 4096) {
            return false;
        }
        OperationMarker existing = operationMarkers.get(operationId);
        if (existing != null) {
            return operationType.equals(existing.getOperationType())
                    && intent.equals(existing.getIntent());
        }
        operationMarkers.put(operationId, new OperationMarker(operationId, operationType, intent));
        return true;
    }

    /** Records the inventory-side effect after it has been applied to the live player. */
    public synchronized boolean markOperationInventoryApplied(UUID operationId) {
        OperationMarker marker = operationMarkers.get(operationId);
        if (marker == null) return false;
        marker.setInventoryApplied(true);
        return true;
    }

    /** Removes a completed marker as a separate, idempotent durable cleanup. */
    public synchronized boolean removeOperationMarker(UUID operationId) {
        return operationId != null && operationMarkers.remove(operationId) != null;
    }

    public synchronized Map<UUID, OperationMarker> getOperationMarkers() {
        return Collections.unmodifiableMap(deepCopyOperationMarkers(operationMarkers));
    }

    public synchronized void setOperationMarkers(Map<UUID, OperationMarker> operationMarkers) {
        this.operationMarkers = operationMarkers != null ? operationMarkers : new HashMap<>();
    }

    /**
     * Read-only view of a tab's contents. Never materializes an entry — an
     * out-of-range or never-used index simply yields an empty list, so callers
     * passing unvalidated input (e.g. network payloads) cannot pollute the map.
     */
    public synchronized List<VaultItem> getTabItems(int tabIndex) {
        return Collections.unmodifiableList(tabs.getOrDefault(tabIndex, List.of()));
    }

    /** Internal mutable view — only reached by methods that have already bounds-checked the index. */
    private List<VaultItem> mutableItems(int tabIndex) {
        return tabs.computeIfAbsent(tabIndex, k -> new ArrayList<>());
    }

    public synchronized boolean deposit(int tabIndex, int slotIndex, String itemId, int count) {
        return deposit(tabIndex, slotIndex, itemId, count, null);
    }

    public synchronized boolean deposit(int tabIndex, int slotIndex, String itemId, int count, String tag) {
        if (tabIndex < 0 || tabIndex >= unlockedTabs || slotIndex < 0 || slotIndex >= 54 || count <= 0) {
            return false;
        }
        List<VaultItem> items = mutableItems(tabIndex);
        // Check if slot already occupied
        for (VaultItem item : items) {
            if (item.getSlot() == slotIndex) {
                if (item.getItemId().equals(itemId) && Objects.equals(item.getTag(), tag)) {
                    long sum = (long) item.getCount() + (long) count;
                    item.setCount((int) Math.min(Integer.MAX_VALUE, sum));
                    return true;
                }
                return false; // occupied by different item or different component tag
            }
        }
        items.add(new VaultItem(slotIndex, itemId, count, tag));
        return true;
    }

    /**
     * Deposit into the first compatible slot: merges onto an existing stack of the same
     * itemId+tag, otherwise claims the lowest free slot. Returns the slot used, or -1 if the
     * tab is full or the tab index is locked/invalid.
     */
    public synchronized int depositAuto(int tabIndex, String itemId, int count, String tag) {
        if (tabIndex < 0 || tabIndex >= unlockedTabs || itemId == null || itemId.isBlank() || count <= 0) {
            return -1;
        }
        List<VaultItem> items = mutableItems(tabIndex);
        for (VaultItem item : items) {
            if (item.getItemId().equals(itemId) && Objects.equals(item.getTag(), tag)) {
                long sum = (long) item.getCount() + (long) count;
                item.setCount((int) Math.min(Integer.MAX_VALUE, sum));
                return item.getSlot();
            }
        }
        for (int slot = 0; slot < 54; slot++) {
            int candidate = slot;
            if (items.stream().noneMatch(i -> i.getSlot() == candidate)) {
                items.add(new VaultItem(slot, itemId, count, tag));
                return slot;
            }
        }
        return -1;
    }

    public synchronized Optional<VaultItem> withdraw(int tabIndex, int slotIndex, int count) {
        if (tabIndex < 0 || tabIndex >= unlockedTabs || slotIndex < 0 || slotIndex >= 54 || count <= 0) {
            return Optional.empty();
        }
        List<VaultItem> items = mutableItems(tabIndex);
        for (Iterator<VaultItem> it = items.iterator(); it.hasNext(); ) {
            VaultItem item = it.next();
            if (item.getSlot() == slotIndex) {
                if (item.getCount() <= count) {
                    it.remove();
                    return Optional.of(item);
                } else {
                    item.setCount(item.getCount() - count);
                    return Optional.of(new VaultItem(slotIndex, item.getItemId(), count, item.getTag()));
                }
            }
        }
        return Optional.empty();
    }
}
