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

        public VaultItem() {}

        public VaultItem(int slot, String itemId, int count) {
            this.slot = slot;
            this.itemId = itemId != null ? itemId : "";
            this.count = count;
        }

        public int getSlot() { return slot; }
        public void setSlot(int slot) { this.slot = slot; }

        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }

    @JsonProperty(required = true)
    private UUID playerUuid;

    @JsonProperty
    private int unlockedTabs = 1;

    @JsonProperty
    private Map<Integer, List<VaultItem>> tabs = new HashMap<>();

    public BankVault() {}

    public BankVault(UUID playerUuid) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.tabs.put(0, new ArrayList<>());
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }

    public int getUnlockedTabs() { return unlockedTabs; }
    public void setUnlockedTabs(int unlockedTabs) { this.unlockedTabs = unlockedTabs; }

    public Map<Integer, List<VaultItem>> getTabs() { return tabs; }
    public void setTabs(Map<Integer, List<VaultItem>> tabs) {
        this.tabs = tabs != null ? tabs : new HashMap<>();
    }

    public List<VaultItem> getTabItems(int tabIndex) {
        return tabs.computeIfAbsent(tabIndex, k -> new ArrayList<>());
    }

    public boolean deposit(int tabIndex, int slotIndex, String itemId, int count) {
        if (tabIndex < 0 || tabIndex >= unlockedTabs || count <= 0) {
            return false;
        }
        List<VaultItem> items = getTabItems(tabIndex);
        // Check if slot already occupied
        for (VaultItem item : items) {
            if (item.getSlot() == slotIndex) {
                if (item.getItemId().equals(itemId)) {
                    item.setCount(item.getCount() + count);
                    return true;
                }
                return false; // occupied by different item
            }
        }
        items.add(new VaultItem(slotIndex, itemId, count));
        return true;
    }

    public Optional<VaultItem> withdraw(int tabIndex, int slotIndex, int count) {
        if (tabIndex < 0 || tabIndex >= unlockedTabs || count <= 0) {
            return Optional.empty();
        }
        List<VaultItem> items = getTabItems(tabIndex);
        for (Iterator<VaultItem> it = items.iterator(); it.hasNext(); ) {
            VaultItem item = it.next();
            if (item.getSlot() == slotIndex) {
                if (item.getCount() <= count) {
                    it.remove();
                    return Optional.of(item);
                } else {
                    item.setCount(item.getCount() - count);
                    return Optional.of(new VaultItem(slotIndex, item.getItemId(), count));
                }
            }
        }
        return Optional.empty();
    }
}