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

        // 5. Top-level section check (and nested-sibling check, #381).
        validateTopLevelSections(ctx, interpolated, new LinkedHashSet<>(prefixToTypes.keySet()));

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

    /**
     * Top-level section check. A top-level YAML key is accepted if it matches a claimed prefix
     * literally (flat-dotted form, e.g. {@code "tiko.kafka":}) — its binder then owns everything
     * below — or if it is the first segment of a dotted prefix (nested form, e.g. {@code tiko:
     * kafka: ...} for prefix {@code tiko.kafka}), in which case its children are walked so a typo'd
     * sibling that matches no claimed prefix is reported rather than binding silently against
     * layered defaults (#381). Anything else is an unknown top-level section.
     */
    private static void validateTopLevelSections(
            BindContext ctx, Map<String, Object> interpolated, Set<String> claimed) {
        Set<String> claimedFirstSegments = new LinkedHashSet<>();
        for (String p : claimed) {
            int dot = p.indexOf('.');
            claimedFirstSegments.add(dot < 0 ? p : p.substring(0, dot));
        }
        for (Map.Entry<String, Object> entry : interpolated.entrySet()) {
            String k = entry.getKey();
            if (claimed.contains(k)) {
                // exact claimed prefix (flat-dotted form) — its binder owns everything below
            } else if (claimedFirstSegments.contains(k)) {
                validateNestedSections(ctx, k, entry.getValue(), claimed);
            } else {
                String hint = NearestKey.hint(k, claimed, java.util.function.UnaryOperator.identity());
                ctx.reportAtPath(ConfigIssueCode.UNKNOWN_SECTION, k, "unknown top-level section '" + k + "'." + hint);
            }
        }
    }

    /**
     * Validates the nested children of an intermediate path (#381). {@code path} matched the first
     * segment of some claimed prefix but is not itself a claimed prefix, so its sub-map must lead
     * only toward claimed prefixes. A child whose full path is a claimed prefix is left to that
     * prefix's binder (it owns everything below). A child that merely extends the claimed tree
     * (a deeper intermediate node) recurses. Any other child is an unknown section — the case a
     * typo like {@code tiko.kavka} hits, which previously bound silently against layered defaults.
     */
    private static void validateNestedSections(BindContext ctx, String path, Object value, Set<String> claimed) {
        if (!(value instanceof Map<?, ?> node)) {
            return; // not a nested mapping — a type mismatch is the binder's concern, not ours
        }
        // Valid next segments at this depth: the segment following `path` in every claimed prefix
        // that strictly extends it — the "did you mean" candidates for a sibling typo.
        Set<String> continuations = new LinkedHashSet<>();
        for (String prefix : claimed) {
            if (prefix.startsWith(path + ".")) {
                String rest = prefix.substring(path.length() + 1);
                int dot = rest.indexOf('.');
                continuations.add(dot < 0 ? rest : rest.substring(0, dot));
            }
        }
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String child = String.valueOf(entry.getKey());
            String childPath = path + "." + child;
            if (claimed.contains(childPath)) {
                // reached a claimed prefix — its binder owns everything below
            } else if (isIntermediateOf(childPath, claimed)) {
                validateNestedSections(ctx, childPath, entry.getValue(), claimed);
            } else {
                String hint = NearestKey.hint(child, continuations, c -> path + "." + c);
                ctx.reportAtPath(
                        ConfigIssueCode.UNKNOWN_SECTION,
                        childPath,
                        "unknown config section '" + childPath + "'." + hint);
            }
        }
    }

    /** True when {@code path} is a strict prefix of some claimed prefix (i.e. a deeper intermediate node). */
    private static boolean isIntermediateOf(String path, Set<String> claimed) {
        for (String prefix : claimed) {
            if (prefix.startsWith(path + ".")) {
                return true;
            }
        }
        return false;
    }
}
