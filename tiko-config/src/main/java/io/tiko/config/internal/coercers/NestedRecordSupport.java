// tiko-config/src/main/java/io/tiko/config/internal/coercers/NestedRecordSupport.java
package io.tiko.config.internal.coercers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Internal helpers used exclusively by generated nested-record coercers.
 *
 * <p>Nested records are bound by stateless {@link TypeCoercer}s (no
 * {@link io.tiko.config.BindContext}) so all error reporting goes through
 * {@link CoercionException}: messages are prepended with the field name and
 * the outer {@code BindContext} anchors them to the full configuration path.
 *
 * <p>Not part of the public API.
 */
public final class NestedRecordSupport {

    private NestedRecordSupport() {}

    /**
     * Casts the raw YAML node to a mutable {@code Map<String, Object>}, mirroring
     * {@link io.tiko.config.BindContext#requireSection} but throwing {@link CoercionException}
     * instead of accumulating an error (the outer binder anchors the path).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireMap(Object raw, String recordName) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new CoercionException(
                "expected mapping for nested record '" + recordName + "', got " + describe(raw));
        }
        return new LinkedHashMap<>((Map<String, Object>) m);
    }

    /**
     * Reads a required field from a nested record's node. Throws {@link CoercionException}
     * with the record + field path prepended on missing key or coercion failure.
     */
    public static <T> T requireField(Map<String, Object> node, String fieldName,
            String recordName, TypeCoercer<T> coercer) {
        if (!node.containsKey(fieldName)) {
            throw new CoercionException(
                recordName + " missing required field '" + fieldName + "'");
        }
        Object raw = node.remove(fieldName);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            throw new CoercionException(recordName + "." + fieldName + " " + e.getMessage());
        }
    }

    /**
     * Reads a defaulted field from a nested record's node. Returns {@code defaultValue}
     * if the key is absent.
     */
    public static <T> T fieldOrDefault(Map<String, Object> node, String fieldName,
            String recordName, TypeCoercer<T> coercer, T defaultValue) {
        if (!node.containsKey(fieldName)) {
            return defaultValue;
        }
        Object raw = node.remove(fieldName);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            throw new CoercionException(recordName + "." + fieldName + " " + e.getMessage());
        }
    }

    /**
     * Reads an Optional field from a nested record's node. Returns {@link Optional#empty()}
     * if the key is absent.
     */
    public static <T> Optional<T> optionalField(Map<String, Object> node, String fieldName,
            String recordName, TypeCoercer<T> coercer) {
        if (!node.containsKey(fieldName)) {
            return Optional.empty();
        }
        Object raw = node.remove(fieldName);
        try {
            return Optional.of(coercer.coerce(raw));
        } catch (CoercionException e) {
            throw new CoercionException(recordName + "." + fieldName + " " + e.getMessage());
        }
    }

    /**
     * Throws {@link CoercionException} if {@code node} contains any leftover keys after
     * binding consumed all known fields. Strict-mode unknown-key check at the nested level
     * mirrors {@link io.tiko.config.BindContext#checkUnknownKeys} for top-level binders.
     *
     * <p>Throws on the first unknown key — nested binding terminates on first error,
     * unlike top-level binding which accumulates.
     */
    public static void checkUnknownKeys(Map<String, Object> node, String recordName) {
        if (!node.isEmpty()) {
            throw new CoercionException(
                recordName + " unknown key '" + node.keySet().iterator().next() + "'");
        }
    }

    private static String describe(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
