// tiko-config/src/main/java/io/tiko/config/BindContext.java
package io.tiko.config;

import io.tiko.ConfigIssue;
import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.internal.ConfigError;
import io.tiko.config.internal.NearestKey;
import io.tiko.config.internal.coercers.CoercionException;
import io.tiko.config.internal.coercers.TypeCoercer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-bind-call accumulator and read helpers. Generated binders call into this
 * for required-field resolution, default substitution, type coercion, and
 * unknown-key checking. Errors are accumulated rather than thrown — the
 * caller flushes them into {@link ConfigValidationException} once at the end.
 */
public final class BindContext {

    private final String source;
    private final Map<String, SourceLocation> locations;
    private final List<ConfigError> errors = new ArrayList<>();

    public BindContext(String source) {
        this(source, Map.of());
    }

    public BindContext(String source, Map<String, SourceLocation> locations) {
        this.source = source;
        this.locations = Map.copyOf(locations);
    }

    // -- Error accumulation ------------------------------------------------

    /** Reports an error against a known YAML location, using the context's default source. */
    public void reportAt(ConfigIssueCode code, int line, int column, String message) {
        reportAt(code, this.source, line, column, message);
    }

    /** Reports an error against a known YAML location with an explicit source label. */
    public void reportAt(ConfigIssueCode code, String source, int line, int column, String message) {
        errors.add(ConfigError.at(code, source, line, column, message));
    }

    /** Reports an error with no specific location. */
    public void report(ConfigIssueCode code, String message) {
        errors.add(ConfigError.unanchored(code, message));
    }

    /**
     * Reports an error against the known location for {@code dotPath} when one is
     * available, else falls back to {@link #report(ConfigIssueCode, String)} unanchored.
     */
    public void reportAtPath(ConfigIssueCode code, String dotPath, String message) {
        SourceLocation loc = locations.get(dotPath);
        if (loc != null) {
            reportAt(code, loc.source(), loc.line(), loc.column(), message);
        } else {
            report(code, message);
        }
    }

    private static String parentSectionPath(String fullPath) {
        int lastDot = fullPath.lastIndexOf('.');
        return lastDot < 0 ? fullPath : fullPath.substring(0, lastDot);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<ConfigError> errors() {
        return List.copyOf(errors);
    }

    /**
     * Returns the accumulated errors as public {@link ConfigIssue} records — anchor info
     * (source/line/column) folded into each issue's description string. Used by
     * {@link ConfigValidationException} and by {@code Tiko.create(...)} to populate the
     * {@code ConfigurationFailure} ErrorContext.
     */
    public List<ConfigIssue> issues() {
        List<ConfigIssue> out = new ArrayList<>(errors.size());
        for (ConfigError e : errors) {
            String desc =
                    e.line() > 0 ? e.source() + ":" + e.line() + ":" + e.column() + " " + e.message() : e.message();
            out.add(new ConfigIssue(e.code(), desc));
        }
        return List.copyOf(out);
    }

    public String source() {
        return source;
    }

    // -- Section navigation ------------------------------------------------

    /**
     * Returns the sub-map at {@code key}, or an empty map (with an error) if absent.
     * The returned map is read-only; binders consume keys from it via the scalar
     * read methods which remove consumed keys, enabling {@link #checkUnknownKeys}.
     *
     * <p>A dotted {@code key} (e.g. {@code "tiko.kafka"}) is resolved by walking the
     * nested map path — {@code root.get("tiko").get("kafka")} — matching how users
     * naturally write multi-segment configs in YAML. If the nested form isn't found,
     * falls back to a literal flat-key lookup ({@code root.get("tiko.kafka")}) so
     * existing flat-dotted YAML (e.g. {@code tiko-kafka}'s baked-in defaults.yaml)
     * keeps working unchanged.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> requireSection(Map<String, Object> root, String key) {
        Object v = resolveByDottedPath(root, key);
        if (v == null) {
            v = root.get(key);
        }
        if (v == null) {
            reportAtPath(ConfigIssueCode.MISSING_SECTION, key, "missing required section '" + key + "'");
            return new LinkedHashMap<>();
        }
        if (!(v instanceof Map<?, ?>)) {
            reportAtPath(
                    ConfigIssueCode.INVALID_VALUE,
                    key,
                    "expected section '" + key + "' to be a mapping, got "
                            + v.getClass().getSimpleName());
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>((Map<String, Object>) v);
    }

    /**
     * Walks a dotted path through nested maps. Returns {@code null} when any segment
     * is absent or a non-map. Single-segment keys delegate to a normal {@code get}.
     */
    @SuppressWarnings("unchecked")
    private static Object resolveByDottedPath(Map<String, Object> root, String dottedKey) {
        if (!dottedKey.contains(".")) {
            return root.get(dottedKey);
        }
        Object current = root;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(segment);
            if (current == null) return null;
        }
        return current;
    }

    // -- Field reads -------------------------------------------------------

    /**
     * Reads a required scalar at {@code key} from {@code node}, applying {@code coercer}.
     * On absence, accumulates an error and returns {@code fallback}.
     * On coercion failure, accumulates an error and returns {@code fallback}.
     * The key is removed from {@code node} on success so {@link #checkUnknownKeys}
     * can detect leftovers.
     */
    public <T> T requireScalar(
            Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer, T fallback) {
        if (!node.containsKey(key)) {
            reportAtPath(
                    ConfigIssueCode.MISSING_KEY, parentSectionPath(fullPath), fullPath + " is required but missing");
            return fallback;
        }
        Object raw = node.remove(key);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            reportAtPath(ConfigIssueCode.INVALID_VALUE, fullPath, fullPath + " " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Reads {@code key} from {@code node} if present (applying {@code coercer}),
     * otherwise returns {@code defaultValue}. Coercion failure produces an error
     * and the default is returned.
     */
    public <T> T scalarOrDefault(
            Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer, T defaultValue) {
        if (!node.containsKey(key)) return defaultValue;
        Object raw = node.remove(key);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            reportAtPath(ConfigIssueCode.INVALID_VALUE, fullPath, fullPath + " " + e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Reads {@code key} as {@code Optional<T>} — present-and-coerced if the key exists,
     * empty if absent.
     */
    public <T> Optional<T> optionalScalar(
            Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer) {
        if (!node.containsKey(key)) return Optional.empty();
        Object raw = node.remove(key);
        try {
            return Optional.of(coercer.coerce(raw));
        } catch (CoercionException e) {
            reportAtPath(ConfigIssueCode.INVALID_VALUE, fullPath, fullPath + " " + e.getMessage());
            return Optional.empty();
        }
    }

    // -- Unknown-key check -------------------------------------------------

    /**
     * Emits one error per remaining key in {@code node} after binding consumed
     * all the known fields. Generated binders pass the set of consumed keys.
     */
    public void checkUnknownKeys(Map<String, Object> node, String sectionPath, Set<String> known) {
        for (String k : node.keySet()) {
            if (!known.contains(k)) {
                String hint = NearestKey.hint(k, known, suggestion -> sectionPath + "." + suggestion);
                reportAtPath(
                        ConfigIssueCode.UNKNOWN_KEY,
                        sectionPath + "." + k,
                        "unknown key '" + sectionPath + "." + k + "'." + hint);
            }
        }
    }
}
