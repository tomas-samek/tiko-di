// tiko-config/src/main/java/io/tiko/config/internal/coercers/Coercers.java
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundled coercer factories. Each method returns a {@link TypeCoercer} for one
 * supported scalar / leaf type. Collections, optionals, and nested records are
 * handled by composite coercers in {@link CompositeCoercers}.
 */
public final class Coercers {

    private Coercers() {}

    public static TypeCoercer<Integer> intCoercer() {
        return v -> {
            if (v instanceof Integer i) return i;
            if (v instanceof Long l) {
                try {
                    return Math.toIntExact(l);
                } catch (ArithmeticException e) {
                    throw new CoercionException("expected integer, got long " + l + " (out of int range)");
                }
            }
            if (v instanceof String s)
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    throw new CoercionException("expected integer, got string \"" + s + "\"");
                }
            throw new CoercionException("expected integer, got " + describe(v));
        };
    }

    public static TypeCoercer<Long> longCoercer() {
        return v -> {
            if (v instanceof Long l) return l;
            if (v instanceof Integer i) return i.longValue();
            if (v instanceof String s)
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException e) {
                    throw new CoercionException("expected long, got string \"" + s + "\"");
                }
            throw new CoercionException("expected long, got " + describe(v));
        };
    }

    public static TypeCoercer<Boolean> booleanCoercer() {
        return v -> {
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) {
                String t = s.trim().toLowerCase();
                if (t.equals("true")) return Boolean.TRUE;
                if (t.equals("false")) return Boolean.FALSE;
                throw new CoercionException("expected boolean, got string \"" + s + "\"");
            }
            throw new CoercionException("expected boolean, got " + describe(v));
        };
    }

    public static TypeCoercer<Double> doubleCoercer() {
        return v -> {
            if (v instanceof Double d) return d;
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s)
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    throw new CoercionException("expected double, got string \"" + s + "\"");
                }
            throw new CoercionException("expected double, got " + describe(v));
        };
    }

    public static TypeCoercer<Float> floatCoercer() {
        return v -> {
            if (v instanceof Float f) return f;
            if (v instanceof Number n) return n.floatValue();
            if (v instanceof String s)
                try {
                    return Float.parseFloat(s.trim());
                } catch (NumberFormatException e) {
                    throw new CoercionException("expected float, got string \"" + s + "\"");
                }
            throw new CoercionException("expected float, got " + describe(v));
        };
    }

    public static TypeCoercer<Short> shortCoercer() {
        return v -> {
            int i = intCoercer().coerce(v);
            if (i < Short.MIN_VALUE || i > Short.MAX_VALUE)
                throw new CoercionException("value " + i + " out of short range");
            return (short) i;
        };
    }

    public static TypeCoercer<Byte> byteCoercer() {
        return v -> {
            int i = intCoercer().coerce(v);
            if (i < Byte.MIN_VALUE || i > Byte.MAX_VALUE)
                throw new CoercionException("value " + i + " out of byte range");
            return (byte) i;
        };
    }

    public static TypeCoercer<Character> charCoercer() {
        return v -> {
            if (v instanceof Character c) return c;
            if (v instanceof String s && s.length() == 1) return s.charAt(0);
            throw new CoercionException("expected single character, got " + describe(v));
        };
    }

    public static TypeCoercer<String> stringCoercer() {
        return v -> v == null ? null : v.toString();
    }

    public static TypeCoercer<Duration> durationCoercer() {
        return parsing(
                "duration (e.g. \"5s\", \"30m\", \"1h\", or ISO-8601 \"PT5S\")", Coercers::parseFriendlyDuration);
    }

    /** Bare integer amount + a single unit suffix — the friendly forms layered over ISO-8601 (#113). */
    private static final Pattern FRIENDLY_DURATION = Pattern.compile("([+-]?\\d+)(ns|ms|s|m|h|d)");

    /**
     * Parses a duration accepting either a friendly {@code <amount><unit>} form ({@code 5s},
     * {@code 30m}, {@code 1h}, {@code 500ms}, {@code -5s}) or ISO-8601 ({@code PT5S}, {@code PT1H30M},
     * {@code P2D}). The friendly form requires an integer amount; anything else (fractions, ISO,
     * garbage) falls through to {@link Duration#parse}, which either succeeds or throws and is
     * surfaced as a {@code CoercionException} by {@link #parsing}.
     */
    private static Duration parseFriendlyDuration(String raw) {
        String value = raw.trim();
        Matcher matcher = FRIENDLY_DURATION.matcher(value);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ns" -> Duration.ofNanos(amount);
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("unreachable duration unit: " + matcher.group(2));
            };
        }
        return Duration.parse(value);
    }

    public static TypeCoercer<Instant> instantCoercer() {
        return parsing("instant", Instant::parse);
    }

    public static TypeCoercer<LocalDate> localDateCoercer() {
        return parsing("local date", LocalDate::parse);
    }

    public static TypeCoercer<LocalDateTime> localDateTimeCoercer() {
        return parsing("local datetime", LocalDateTime::parse);
    }

    public static TypeCoercer<ZoneId> zoneIdCoercer() {
        return parsing("zone id", ZoneId::of);
    }

    public static TypeCoercer<UUID> uuidCoercer() {
        return parsing("UUID", UUID::fromString);
    }

    public static TypeCoercer<URI> uriCoercer() {
        return parsing("URI", URI::create);
    }

    public static TypeCoercer<Path> pathCoercer() {
        return parsing("path", Path::of);
    }

    public static TypeCoercer<Charset> charsetCoercer() {
        return parsing("charset", Charset::forName);
    }

    public static TypeCoercer<BigDecimal> bigDecimalCoercer() {
        return parsing("decimal", BigDecimal::new);
    }

    public static TypeCoercer<Pattern> patternCoercer() {
        return parsing("pattern", Pattern::compile);
    }

    public static <E extends Enum<E>> TypeCoercer<E> enumCoercer(Class<E> type) {
        return v -> {
            if (v == null) throw new CoercionException("expected " + type.getSimpleName() + ", got null");
            String s = stringCoercer().coerce(v);
            try {
                return Enum.valueOf(type, s);
            } catch (IllegalArgumentException e) {
                StringBuilder names = new StringBuilder();
                E[] constants = type.getEnumConstants();
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) names.append(", ");
                    names.append(constants[i].name());
                }
                throw new CoercionException("expected one of [" + names + "], got \"" + s + "\"");
            }
        };
    }

    private static <T> TypeCoercer<T> parsing(String label, java.util.function.Function<String, T> parser) {
        return v -> {
            String s = stringCoercer().coerce(v);
            try {
                return parser.apply(s);
            } catch (RuntimeException e) {
                throw new CoercionException("expected " + label + ", got \"" + s + "\"");
            }
        };
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        return v.getClass().getSimpleName();
    }
}
