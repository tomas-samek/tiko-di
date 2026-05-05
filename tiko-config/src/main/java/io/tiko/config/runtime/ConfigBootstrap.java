package io.tiko.config.runtime;

import io.tiko.ConfigSource;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.internal.Interpolator;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime entry point for typed-config binding. Used by {@code Tiko.create(ConfigSource)}.
 */
public final class ConfigBootstrap {

    private ConfigBootstrap() {}

    /**
     * Loads the YAML, interpolates {@code ${VAR}}s, validates top-level prefixes,
     * runs every binder, and either returns a {@code Map<Class<?>, Object>} of bound
     * records or throws {@link ConfigValidationException} with the full report.
     */
    public static Map<Class<?>, Object> bind(String sourceLabel, ConfigSource source, List<ConfigBinder<?>> binders) {
        BindContext ctx = new BindContext(sourceLabel);

        // 1. Load
        Map<String, Object> raw = source.load();

        // 2. Interpolate
        @SuppressWarnings("unchecked")
        Map<String, Object> interpolated = (Map<String, Object>) Interpolator.interpolate(raw, System::getenv, ctx);

        // 3. Top-level prefix check
        Set<String> claimed = new LinkedHashSet<>();
        for (ConfigBinder<?> b : binders) claimed.add(b.prefix());
        for (String k : interpolated.keySet()) {
            if (!claimed.contains(k)) {
                String suggestion = nearest(k, claimed);
                String hint = suggestion != null ? " Did you mean '" + suggestion + "'?" : "";
                ctx.report("unknown top-level section '" + k + "'." + hint);
            }
        }

        // 4. Bind each record
        Map<Class<?>, Object> bound = new LinkedHashMap<>();
        for (ConfigBinder<?> b : binders) {
            Object instance = b.bind(interpolated, ctx);
            bound.put(b.type(), instance);
        }

        // 5. Throw if anything accumulated
        if (ctx.hasErrors()) {
            throw new ConfigValidationException(sourceLabel, ctx.errors());
        }
        return bound;
    }

    private static String nearest(String input, Set<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(input, c);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return (bestDist <= 2) ? best : null;
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
