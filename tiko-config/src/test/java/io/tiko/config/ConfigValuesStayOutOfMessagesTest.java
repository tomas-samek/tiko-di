package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tiko.SourceLocation;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CoercionException;
import io.tiko.config.internal.coercers.CompositeCoercers;
import io.tiko.config.internal.coercers.TypeCoercer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Resolved configuration values never appear in framework log lines or validation messages
 * (SEC-2, #474): a secret placed in — or mistyped into — a config field must not reach the
 * application log. Messages still name the field, the source location and the kind of problem.
 */
class ConfigValuesStayOutOfMessagesTest {

    private static final String SECRET = "hunter2-s3cr3t";

    @BeforeEach
    void clearCapturedLogs() {
        CapturingLoggerFinder.clear();
    }

    static Stream<Arguments> rejectedValues() {
        return Stream.of(
                Arguments.of("int", Coercers.intCoercer(), SECRET, SECRET, "expected integer"),
                Arguments.of("int from long", Coercers.intCoercer(), 98765432109L, "98765432109", "out of int range"),
                Arguments.of("long", Coercers.longCoercer(), SECRET, SECRET, "expected long"),
                Arguments.of("boolean", Coercers.booleanCoercer(), SECRET, SECRET, "expected boolean"),
                Arguments.of("double", Coercers.doubleCoercer(), SECRET, SECRET, "expected double"),
                Arguments.of("float", Coercers.floatCoercer(), SECRET, SECRET, "expected float"),
                Arguments.of("short", Coercers.shortCoercer(), 54321, "54321", "out of short range"),
                Arguments.of("byte", Coercers.byteCoercer(), 4321, "4321", "out of byte range"),
                Arguments.of("enum", Coercers.enumCoercer(Kind.class), SECRET, SECRET, "expected one of [RED, BLUE]"),
                Arguments.of("duration", Coercers.durationCoercer(), SECRET, SECRET, "expected duration"),
                Arguments.of("uuid", Coercers.uuidCoercer(), SECRET, SECRET, "expected UUID"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedValues")
    void coercionMessageNamesTheKindButNotTheValue(
            String name, TypeCoercer<?> coercer, Object input, String leaked, String kind) {
        var e = catchThrowableOfType(CoercionException.class, () -> coercer.coerce(input));

        assertThat(e.getMessage()).contains(kind).doesNotContain(leaked);
    }

    @Test
    void typeMismatchIssueNamesFieldAndLocationButNotTheValue() {
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        locations.put("db.port", new SourceLocation("app.yaml", 4, 9));
        var ctx = new BindContext("app.yaml", locations);
        Map<String, Object> db = new LinkedHashMap<>(Map.of("port", SECRET));

        ctx.requireScalar(db, "port", "db.port", Coercers.intCoercer(), 0);

        assertThat(ctx.issues())
                .singleElement()
                .satisfies(issue -> assertThat(issue.description())
                        .startsWith("app.yaml:4:9 db.port ")
                        .contains("expected integer")
                        .doesNotContain(SECRET));
    }

    @Test
    void duplicateSetElementWarningLocatesTheElementButNotTheValue() {
        TypeCoercer<java.util.Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());

        c.coerce(List.of("token-a", SECRET, "token-b", SECRET));

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.level() == System.Logger.Level.WARNING)
                .filteredOn(r -> "io.tiko.config".equals(r.loggerName()))
                .extracting(CapturingLoggerFinder.LogEntry::message)
                .singleElement()
                .asString()
                .contains("duplicate", "index 3")
                .doesNotContain(SECRET);
    }

    enum Kind {
        RED,
        BLUE
    }
}
