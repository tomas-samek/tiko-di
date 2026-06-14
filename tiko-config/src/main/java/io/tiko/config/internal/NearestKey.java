package io.tiko.config.internal;

import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Nearest-match "did you mean" suggestion for config keys, shared by the top-level
 * section check ({@link io.tiko.config.runtime.ConfigBootstrap}) and the field-level
 * unknown-key checks ({@link io.tiko.config.BindContext} and
 * {@link io.tiko.config.internal.coercers.NestedRecordSupport}).
 *
 * <p>Tiko keeps exact-key validation by design (no kebab-case/snake_case aliasing);
 * this only makes the resulting error teach the right key faster. A candidate is
 * suggested only when its Levenshtein distance to the offending key is small (≤ 2),
 * so unrelated keys produce no misleading hint.
 *
 * <p>Not part of the public API.
 */
public final class NearestKey {

    private static final int MAX_DISTANCE = 2;

    private NearestKey() {}

    /**
     * Returns the candidate closest to {@code input} when it is within
     * {@link #MAX_DISTANCE} edits, else {@code null}.
     */
    public static String suggest(String input, Set<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(input, c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return bestDist <= MAX_DISTANCE ? best : null;
    }

    /**
     * Renders a {@code " Did you mean '<display>'?"} fragment for the nearest candidate,
     * or an empty string when nothing is close enough. {@code display} maps the bare
     * matched candidate to how it should be shown (e.g. prefixed with the section path);
     * pass {@link UnaryOperator#identity()} to show the candidate verbatim.
     */
    public static String hint(String input, Set<String> candidates, UnaryOperator<String> display) {
        String suggestion = suggest(input, candidates);
        return suggestion != null ? " Did you mean '" + display.apply(suggestion) + "'?" : "";
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[b.length()];
    }
}
