package com.storynpcs.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded replay-detection map keyed by request id. Entries are evicted
 * oldest-first when the cap is reached — eviction must never drop a younger
 * request record before an older one, which an unordered
 * {@code ConcurrentHashMap.keySet().iterator()} eviction does not guarantee.
 * In-process replay records are a fast path on top of the durable journals;
 * once the oldest entries age out, a post-eviction replay of that request id
 * falls through to the durable/natural-idempotency checks instead of being
 * answered from memory.
 */
final class BoundedReplayCache<K, V> {

    private final Map<K, V> map;

    BoundedReplayCache(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be >= 1");
        this.map = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    V get(K key) { return map.get(key); }

    void put(K key, V value) { map.put(key, value); }

    int size() { return map.size(); }
}
