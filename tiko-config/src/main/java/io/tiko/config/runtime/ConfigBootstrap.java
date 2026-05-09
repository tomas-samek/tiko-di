package io.tiko.config.runtime;

import io.tiko.ConfigSource;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.internal.Interpolator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

        // 3. Cross-module prefix collision check (#18) — two modules cannot
        // independently claim the same @Configuration prefix without one silently
        // overwriting the other. Report each colliding prefix once, naming all the
        // record types that share it.
        Map<String, List<Class<?>>> prefixToTypes = new LinkedHashMap<>();
        for (ConfigBinder<?> b : binders) {
            prefixToTypes.computeIfAbsent(b.prefix(), p -> new ArrayList<>()).add(b.type());
        }
        for (Map.Entry<String, List<Class<?>>> e : prefixToTypes.entrySet()) {
            if (e.getValue().size() > 1) {
                String types = e.getValue().stream().map(Class::getName).collect(Collectors.joining(", "));
                ctx.report("duplicate @Configuration prefix '" + e.getKey()
                        + "' declared by: " + types
                        + ". Each prefix must be unique across all modules.");
            }
        }

        // 4. Top-level prefix check
        Set<String> claimed = new LinkedHashSet<>(prefixToTypes.keySet());
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
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
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
