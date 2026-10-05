package com.storynpcs.domain.namegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Clean-room seeded Markov name generator (issue #123). A character-level
 * chain of the dictionary's authored order is built from its seed names —
 * each name is padded with start sentinels and terminated with an end
 * sentinel — then a {@link Random} driven by the caller's seed walks the
 * chain until the end sentinel or the length cap. The same dictionary +
 * seed always produces the same name; nothing is shared between calls and
 * no target dictionary text is used.
 */
public final class MarkovNameGenerator {

    private MarkovNameGenerator() {}

    private static final char START = '\u0000';
    private static final char END = '\u0001';
    private static final int MAX_GENERATED_LENGTH = NameDictionary.MAX_NAME_LENGTH;
    private static final int MAX_ATTEMPTS = 32;

    /** Deterministic name for the dictionary's culture under {@code seed}. */
    public static Optional<String> generate(NameDictionary dictionary, long seed) {
        if (dictionary == null || dictionary.getNames().isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<Character>> chain = buildChain(dictionary.getNames(),
                dictionary.getOrder());
        if (chain.isEmpty()) {
            return Optional.empty();
        }
        // Distinct attempts get independent streams derived from the caller's
        // seed — retrying a degenerate sample cannot change determinism.
        Random root = new Random(seed);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Random walk = new Random(root.nextLong());
            String candidate = sample(chain, dictionary.getOrder(), walk);
            if (candidate != null && candidate.length() >= 3
                    && candidate.length() <= MAX_GENERATED_LENGTH) {
                return Optional.of(capitalize(candidate));
            }
        }
        // Degenerate dictionaries (e.g. all identical one-letter names) can
        // exhaust attempts; fail closed rather than emit a 1-char name.
        return Optional.empty();
    }

    /** context(last `order` chars) -> observed next characters (END for terminal). */
    private static Map<String, List<Character>> buildChain(List<String> names, int order) {
        Map<String, List<Character>> chain = new LinkedHashMap<>();
        for (String raw : names) {
            String name = raw.toLowerCase(java.util.Locale.ROOT);
            String padded = String.valueOf(START).repeat(order) + name + END;
            for (int i = order; i < padded.length(); i++) {
                String context = padded.substring(i - order, i);
                chain.computeIfAbsent(context, k -> new ArrayList<>())
                        .add(padded.charAt(i));
            }
        }
        return chain;
    }

    private static String sample(Map<String, List<Character>> chain, int order, Random rng) {
        StringBuilder out = new StringBuilder();
        String context = String.valueOf(START).repeat(order);
        for (int i = 0; i < MAX_GENERATED_LENGTH + order; i++) {
            List<Character> next = chain.get(context);
            if (next == null || next.isEmpty()) {
                return null; // dead context — retry
            }
            char c = next.get(rng.nextInt(next.size()));
            if (c == END) {
                return out.length() >= 2 ? out.toString() : null;
            }
            out.append(c);
            context = context.substring(1) + c;
        }
        return out.toString();
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name
                : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
