package io.tiko.config.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Recursive map merge with last-wins for scalars and atomic replacement for lists. */
public final class DeepMerge {

    private DeepMerge() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            Object existing = out.get(k);
            if (existing instanceof Map<?, ?> em && v instanceof Map<?, ?> nm) {
                out.put(k, merge((Map<String, Object>) em, (Map<String, Object>) nm));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }
}
