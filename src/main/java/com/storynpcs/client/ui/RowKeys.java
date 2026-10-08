package com.storynpcs.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Identity helpers for refresh-stable selection (issue #201): screens keep
 * a selected row <em>key</em> (id) rather than an index, so a server-issued
 * refresh that inserts or removes rows re-resolves the same row instead of
 * silently selecting a neighbor.
 */
public final class RowKeys {

    private RowKeys() {}

    /**
     * Index of the row whose {@code keyOf} result equals {@code key}, or
     * {@code -1}. Null rows and a null/absent key never match.
     */
    public static <T> int indexOf(List<? extends T> rows,
                                  Function<? super T, String> keyOf, String key) {
        if (rows == null || keyOf == null || key == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            T row = rows.get(i);
            if (row != null && Objects.equals(key, keyOf.apply(row))) return i;
        }
        return -1;
    }
}
