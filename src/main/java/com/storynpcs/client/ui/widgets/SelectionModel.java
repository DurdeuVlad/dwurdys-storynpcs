package com.storynpcs.client.ui.widgets;

import java.util.List;
import java.util.Objects;

/**
 * Pure selection state machine (issue #199): keeps a single selected key over
 * an ordered list of keys. Selection is key-based, so it survives data
 * refreshes that reorder or re-paginate rows — the fix for the audit's F3
 * "no stable selection" class of bug. {@link SelectableList} renders it.
 *
 * @param <K> stable row identity (id, UUID, …) — never the row index
 */
public final class SelectionModel<K> {

    private List<K> keys = List.of();
    private K selected;

    /** Replaces the visible keys; drops the selection if its key vanished. */
    public void setItems(List<K> newKeys) {
        keys = newKeys == null ? List.of() : List.copyOf(newKeys);
        if (selected != null && !keys.contains(selected)) {
            selected = null;
        }
    }

    public List<K> keys() { return keys; }
    public int size() { return keys.size(); }
    public boolean isEmpty() { return keys.isEmpty(); }

    public K selected() { return selected; }

    public int selectedIndex() {
        return selected == null ? -1 : keys.indexOf(selected);
    }

    public boolean select(K key) {
        if (key != null && keys.contains(key) && !Objects.equals(selected, key)) {
            selected = key;
            return true;
        }
        return false;
    }

    public boolean selectIndex(int index) {
        return index >= 0 && index < keys.size() && select(keys.get(index));
    }

    public void clear() { selected = null; }

    /**
     * Keyboard navigation: moves selection by {@code delta} rows, clamped to
     * the list. With nothing selected, delta>0 picks the first row and
     * delta<0 the last — matching vanilla list conventions.
     */
    public boolean move(int delta) {
        if (keys.isEmpty() || delta == 0) {
            return false;
        }
        int current = selectedIndex();
        int target = current < 0
                ? (delta > 0 ? 0 : keys.size() - 1)
                : Math.max(0, Math.min(keys.size() - 1, current + delta));
        return selectIndex(target);
    }
}
