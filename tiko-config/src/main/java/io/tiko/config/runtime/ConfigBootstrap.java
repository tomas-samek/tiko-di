package io.tiko.config.runtime;

import io.tiko.ConfigIssueCode;
import io.tiko.ConfigSource;
import io.tiko.ConfigurationFailure;
import io.tiko.ErrorHandler;
import io.tiko.SourceLocation;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.internal.Interpolator;
import io.tiko.config.internal.NearestKey;
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
     * Three-arg form for direct callers (tests, ad-hoc tools). Equivalent to
     * {@link #bind(String, ConfigSource, List, ErrorHandler)} with a {@code null}
     * error handler — failures still throw, just without observability routing.
     */
    public static Map<Class<?>, Object> bind(String sourceLabel, ConfigSource source, List<ConfigBinder<?>> binders) {
        return bind(sourceLabel, source, binders, null);
    }

    /**
     * Loads the YAML, interpolates {@code ${VAR}}s, validates top-level prefixes,
     * runs every binder, and either returns a {@code Map<Class<?>, Object>} of bound
     * records or throws {@link ConfigValidationException} with the full report.
     *
     * <p>When {@code errorHandler} is non-{@code null} and binding accumulates errors,
     * a single {@link ConfigurationFailure} carrying every issue is dispatched through
     * the handler immediately before the exception is thrown.
     */
    public static Map<Class<?>, Object> bind(
            String sourceLabel, ConfigSource source, List<ConfigBinder<?>> binders, ErrorHandler errorHandler) {
        // 1. Load
        Map<String, Object> raw = source.load();
        Map<String, SourceLocation> locations = source.locations();
        BindContext ctx = new BindContext(sourceLabel, locations);

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
                ctx.report(
                        ConfigIssueCode.DUPLICATE_PREFIX,
                        "duplicate @Configuration prefix '" + e.getKey()
                                + "' declared by: " + types
                                + ". Each prefix must be unique across all modules.");
            }
        }

        // 5. Top-level prefix check. A top-level YAML key is accepted if it matches a
        // claimed prefix literally (flat-dotted form, e.g. `"tiko.kafka":`) OR if it's
        // the first segment of any dotted prefix (nested form, e.g. `tiko: kafka: ...`
        // for prefix `tiko.kafka`). Both forms route to the same binder via
        // BindContext.requireSection.
        Set<String> claimed = new LinkedHashSet<>(prefixToTypes.keySet());
        Set<String> claimedFirstSegments = new LinkedHashSet<>();
        for (String p : claimed) {
            int dot = p.indexOf('.');
            claimedFirstSegments.add(dot < 0 ? p : p.substring(0, dot));
        }
        for (String k : interpolated.keySet()) {
            if (!claimed.contains(k) && !claimedFirstSegments.contains(k)) {
                String hint = NearestKey.hint(k, claimed, java.util.function.Function.identity());
                ctx.reportAtPath(ConfigIssueCode.UNKNOWN_SECTION, k, "unknown top-level section '" + k + "'." + hint);
            }
        }

        // 6. Bind each record
        Map<Class<?>, Object> bound = new LinkedHashMap<>();
        for (ConfigBinder<?> b : binders) {
            Object instance = b.bind(interpolated, ctx);
            bound.put(b.type(), instance);
        }

        // 7. Throw if anything accumulated, after routing the bundled failure through
        // the user's ErrorHandler so observability code sees it before the exception
        // surfaces from Tiko.create(...).
        if (ctx.hasErrors()) {
            var issues = ctx.issues();
            var cve = new ConfigValidationException(sourceLabel, issues);
            if (errorHandler != null) {
                errorHandler.onError(new ConfigurationFailure(issues, cve));
            }
            throw cve;
        }
        return bound;
    }
}
