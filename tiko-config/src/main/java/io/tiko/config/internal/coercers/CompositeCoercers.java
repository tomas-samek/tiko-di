// tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java
package io.tiko.config.internal.coercers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coercers for {@code List<X>}, {@code Map<String,X>}, {@code Optional<X>}. */
public final class CompositeCoercers {

    private CompositeCoercers() {}

    public static <X> TypeCoercer<List<X>> list(TypeCoercer<X> elementCoercer) {
        return v -> {
            if (!(v instanceof List<?> raw)) {
                throw new CoercionException("expected list, got "
                        + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            List<X> out = new ArrayList<>(raw.size());
            for (Object e : raw) out.add(elementCoercer.coerce(e));
            return List.copyOf(out);
        };
    }

    public static <X> TypeCoercer<Map<String, X>> map(TypeCoercer<X> valueCoercer) {
        return v -> {
            if (!(v instanceof Map<?, ?> raw)) {
                throw new CoercionException("expected map, got "
                        + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            Map<String, X> out = new LinkedHashMap<>(raw.size());
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                out.put(entry.getKey().toString(), valueCoercer.coerce(entry.getValue()));
            }
            return Map.copyOf(out);
        };
    }

    public static <X> TypeCoercer<Optional<X>> optional(TypeCoercer<X> innerCoercer) {
        return v -> v == null ? Optional.empty() : Optional.of(innerCoercer.coerce(v));
    }
}
