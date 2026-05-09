// tiko-config/src/test/java/io/tiko/config/internal/coercers/CoercersTest.java
package io.tiko.config.internal.coercers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

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

    @Test
    void uri_path_charset_pattern_bigdecimal_zoneId_localDateTime_round_trip() {
        assertThat(Coercers.uriCoercer().coerce("https://example.com")).isEqualTo(URI.create("https://example.com"));
        assertThat(Coercers.pathCoercer().coerce("/tmp/foo")).isEqualTo(Path.of("/tmp/foo"));
        assertThat(Coercers.charsetCoercer().coerce("UTF-8")).isEqualTo(Charset.forName("UTF-8"));
        assertThat(Coercers.patternCoercer().coerce("[a-z]+").pattern())
                .isEqualTo(Pattern.compile("[a-z]+").pattern());
        assertThat(Coercers.bigDecimalCoercer().coerce("3.14")).isEqualTo(new BigDecimal("3.14"));
        assertThat(Coercers.zoneIdCoercer().coerce("Europe/Prague")).isEqualTo(ZoneId.of("Europe/Prague"));
        assertThat(Coercers.localDateTimeCoercer().coerce("2026-05-04T12:00:00"))
                .isEqualTo(LocalDateTime.parse("2026-05-04T12:00:00"));
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
