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

    /**
     * Replaces the visible keys; drops the selection if its key vanished.
     * Null entries are tolerated (an unkeyed row simply can never be
     * selected) — a malformed payload must not crash the client.
     */
    public void setItems(List<K> newKeys) {
        keys = newKeys == null
                ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(newKeys));
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
     * the list edge. With nothing selected, delta&gt;0 picks the first row and
     * delta&lt;0 the last — matching vanilla list conventions. Null-keyed rows
     * and extra occurrences of the already-selected key are skipped so a
     * malformed payload can never wedge navigation.
     */
    public boolean move(int delta) {
        if (keys.isEmpty() || delta == 0) {
            return false;
        }
        int step = delta > 0 ? 1 : -1;
        int i = selectedIndex() < 0
                ? (step > 0 ? -1 : keys.size())
                : selectedIndex();
        int landed = -1;
        for (int remaining = Math.abs(delta); remaining > 0; ) {
            i += step;
            if (i < 0 || i >= keys.size()) break;
            K k = keys.get(i);
            if (k == null || k.equals(selected)) continue;
            landed = i;
            remaining--;
        }
        return landed >= 0 && selectIndex(landed);
    }
}
