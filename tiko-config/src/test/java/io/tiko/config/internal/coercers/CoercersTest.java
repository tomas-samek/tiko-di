// tiko-config/src/test/java/io/tiko/config/internal/coercers/CoercersTest.java
package io.tiko.config.internal.coercers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CoercersTest {

    @Test
    void int_coercer_parses_yaml_integer() {
        assertThat(Coercers.intCoercer().coerce(42)).isEqualTo(42);
        assertThat(Coercers.intCoercer().coerce("42")).isEqualTo(42);
    }

    @Test
    void int_coercer_rejects_non_integer_string() {
        assertThatThrownBy(() -> Coercers.intCoercer().coerce("ten"))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("expected integer");
    }

    @Test
    void long_coercer_handles_yaml_long_and_string() {
        assertThat(Coercers.longCoercer().coerce(123L)).isEqualTo(123L);
        assertThat(Coercers.longCoercer().coerce("123")).isEqualTo(123L);
    }

    @Test
    void boolean_coercer_handles_yaml_boolean_and_string() {
        assertThat(Coercers.booleanCoercer().coerce(Boolean.TRUE)).isTrue();
        assertThat(Coercers.booleanCoercer().coerce("true")).isTrue();
        assertThat(Coercers.booleanCoercer().coerce("FALSE")).isFalse();
    }

    @Test
    void double_coercer_parses_yaml_number_or_string() {
        assertThat(Coercers.doubleCoercer().coerce(1.5)).isEqualTo(1.5);
        assertThat(Coercers.doubleCoercer().coerce("2.5")).isEqualTo(2.5);
    }

    @Test
    void duration_coercer_parses_iso8601() {
        assertThat(Coercers.durationCoercer().coerce("PT30S")).isEqualTo(Duration.ofSeconds(30));
    }

    static Stream<Arguments> friendlyDurations() {
        return Stream.of(
                Arguments.of("5s", Duration.ofSeconds(5)),
                Arguments.of("30s", Duration.ofSeconds(30)),
                Arguments.of("5m", Duration.ofMinutes(5)),
                Arguments.of("1h", Duration.ofHours(1)),
                Arguments.of("2d", Duration.ofDays(2)),
                Arguments.of("500ms", Duration.ofMillis(500)),
                Arguments.of("100ns", Duration.ofNanos(100)),
                Arguments.of("-5s", Duration.ofSeconds(-5)),
                // ISO-8601 forms keep working unchanged — the friendly path only matches bare
                // <amount><unit>; anything starting with P falls through to Duration.parse.
                Arguments.of("PT5S", Duration.ofSeconds(5)),
                Arguments.of("PT1H30M", Duration.ofHours(1).plusMinutes(30)),
                Arguments.of("P2D", Duration.ofDays(2)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("friendlyDurations")
    void durationCoercerParsesFriendlyAndIsoSyntax(String input, Duration expected) {
        assertThat(Coercers.durationCoercer().coerce(input)).isEqualTo(expected);
    }

    @Test
    void durationCoercerRejectsGarbageNamingBothForms() {
        assertThatThrownBy(() -> Coercers.durationCoercer().coerce("soon"))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("5s") // friendly form named
                .hasMessageContaining("PT5S"); // ISO-8601 form named
    }

    @Test
    void instant_coercer_parses_iso8601() {
        assertThat(Coercers.instantCoercer().coerce("2026-05-04T12:00:00Z"))
                .isEqualTo(Instant.parse("2026-05-04T12:00:00Z"));
    }

    @Test
    void local_date_coercer_parses_iso() {
        assertThat(Coercers.localDateCoercer().coerce("2026-05-04")).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    void uuid_coercer_parses_canonical_string() {
        UUID u = UUID.randomUUID();
        assertThat(Coercers.uuidCoercer().coerce(u.toString())).isEqualTo(u);
    }

    @ParameterizedTest(name = "{0}_coercer_round_trips")
    @MethodSource("roundTripCoercers")
    void coercer_round_trips(String name, Supplier<Object> actual, Object expected) {
        assertThat(actual.get()).isEqualTo(expected);
    }

    static Stream<Arguments> roundTripCoercers() {
        return Stream.of(
                Arguments.of(
                        "uri",
                        (Supplier<Object>) () -> Coercers.uriCoercer().coerce("https://example.com"),
                        URI.create("https://example.com")),
                Arguments.of(
                        "path",
                        (Supplier<Object>) () -> Coercers.pathCoercer().coerce("/tmp/foo"),
                        Path.of("/tmp/foo")),
                Arguments.of(
                        "charset",
                        (Supplier<Object>) () -> Coercers.charsetCoercer().coerce("UTF-8"),
                        StandardCharsets.UTF_8),
                Arguments.of(
                        "pattern",
                        (Supplier<Object>)
                                () -> Coercers.patternCoercer().coerce("[a-z]+").pattern(),
                        Pattern.compile("[a-z]+").pattern()),
                Arguments.of(
                        "bigDecimal",
                        (Supplier<Object>) () -> Coercers.bigDecimalCoercer().coerce("3.14"),
                        new BigDecimal("3.14")),
                Arguments.of(
                        "zoneId",
                        (Supplier<Object>) () -> Coercers.zoneIdCoercer().coerce("Europe/Prague"),
                        ZoneId.of("Europe/Prague")),
                Arguments.of(
                        "localDateTime",
                        (Supplier<Object>) () -> Coercers.localDateTimeCoercer().coerce("2026-05-04T12:00:00"),
                        LocalDateTime.parse("2026-05-04T12:00:00")));
    }

    @Test
    void enum_coercer_matches_name_case_sensitively() {
        TypeCoercer<TestKind> c = Coercers.enumCoercer(TestKind.class);
        assertThat(c.coerce("RED")).isEqualTo(TestKind.RED);
        assertThatThrownBy(() -> c.coerce("red"))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("expected one of [RED, BLUE]");
    }

    @Test
    void int_coercer_rejects_long_overflow() {
        assertThatThrownBy(() -> Coercers.intCoercer().coerce(((long) Integer.MAX_VALUE) + 1L))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("out of int range");
    }

    @Test
    void enum_coercer_rejects_null_input() {
        assertThatThrownBy(() -> Coercers.enumCoercer(TestKind.class).coerce(null))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("got null");
    }

    @Test
    void byte_coercer_rejects_out_of_range() {
        assertThatThrownBy(() -> Coercers.byteCoercer().coerce(200))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("out of byte range");
    }

    enum TestKind {
        RED,
        BLUE
    }
}
