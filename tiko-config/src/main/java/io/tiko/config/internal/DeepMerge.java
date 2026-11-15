package io.tiko.config.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recursive map merge with last-wins for scalars and atomic replacement for lists. A key the overlay
 * writes without a value (a YAML null: {@code url:}, or a section whose keys are all commented out)
 * says nothing, so the base's value stays (#765).
 */
public final class DeepMerge {

    private DeepMerge() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            Object existing = out.get(k);
            if (v == null && existing != null) {
                continue;
            }
            if (existing instanceof Map<?, ?> em && v instanceof Map<?, ?> nm) {
                out.put(k, merge((Map<String, Object>) em, (Map<String, Object>) nm));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }
}
