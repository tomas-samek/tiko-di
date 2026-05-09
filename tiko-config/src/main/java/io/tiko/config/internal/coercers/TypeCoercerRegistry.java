// tiko-config/src/main/java/io/tiko/config/internal/coercers/TypeCoercerRegistry.java
package io.tiko.config.internal.coercers;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Internal closed registry of bundled scalar coercers. Keyed by the boxed
 * representation; primitive types route to their boxed equivalents.
 *
 * <p>Designed so a future public {@code TypeCoercer<T>} SPI is purely additive
 * (e.g. add a {@code register(Class<T>, TypeCoercer<T>)} method without
 * changing existing call sites).</p>
 */
public final class TypeCoercerRegistry {

    private static final Map<Class<?>, TypeCoercer<?>> BUNDLED = new LinkedHashMap<>();

    static {
        BUNDLED.put(Integer.class, Coercers.intCoercer());
        BUNDLED.put(Long.class, Coercers.longCoercer());
        BUNDLED.put(Boolean.class, Coercers.booleanCoercer());
        BUNDLED.put(Double.class, Coercers.doubleCoercer());
        BUNDLED.put(Float.class, Coercers.floatCoercer());
        BUNDLED.put(Short.class, Coercers.shortCoercer());
        BUNDLED.put(Byte.class, Coercers.byteCoercer());
        BUNDLED.put(Character.class, Coercers.charCoercer());
        BUNDLED.put(String.class, Coercers.stringCoercer());
        BUNDLED.put(Duration.class, Coercers.durationCoercer());
        BUNDLED.put(Instant.class, Coercers.instantCoercer());
        BUNDLED.put(LocalDate.class, Coercers.localDateCoercer());
        BUNDLED.put(LocalDateTime.class, Coercers.localDateTimeCoercer());
        BUNDLED.put(ZoneId.class, Coercers.zoneIdCoercer());
        BUNDLED.put(UUID.class, Coercers.uuidCoercer());
        BUNDLED.put(URI.class, Coercers.uriCoercer());
        BUNDLED.put(Path.class, Coercers.pathCoercer());
        BUNDLED.put(Charset.class, Coercers.charsetCoercer());
        BUNDLED.put(BigDecimal.class, Coercers.bigDecimalCoercer());
        BUNDLED.put(Pattern.class, Coercers.patternCoercer());
    }

    private TypeCoercerRegistry() {}

    @SuppressWarnings("unchecked")
    public static <T> TypeCoercer<T> get(Class<T> type) {
        if (type.isEnum()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            TypeCoercer<T> c =
                    (TypeCoercer<T>) Coercers.enumCoercer((Class<? extends Enum>) type.asSubclass(Enum.class));
            return c;
        }
        Class<?> boxed = boxed(type);
        TypeCoercer<?> c = BUNDLED.get(boxed);
        if (c == null) throw new IllegalArgumentException("No bundled coercer for " + type.getName());
        return (TypeCoercer<T>) c;
    }

    public static boolean isSupported(Class<?> type) {
        return type.isEnum() || BUNDLED.containsKey(boxed(type));
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        throw new IllegalArgumentException("Unboxable primitive: " + type);
    }
}
