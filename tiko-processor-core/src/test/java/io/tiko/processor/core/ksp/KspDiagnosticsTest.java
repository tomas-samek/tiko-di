package io.tiko.processor.core.ksp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import io.tiko.processor.core.model.Location;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Diagnostics on KSP go to KSP's logger at the matching severity, placed on the location's node
 * (KSP prints its file and line) or without a position for a {@code null} location.
 */
class KspDiagnosticsTest {

    private static final String KOTLIN = """
            package d

            class Target
            """;

    private static String messages;
    private static final Map<String, Object> facts = new LinkedHashMap<>();

    @BeforeAll
    static void compile() {
        SymbolProcessorProvider probe = environment -> resolver -> {
            if (facts.isEmpty()) {
                var env = KspProcessingEnv.of(
                        resolver, environment.getCodeGenerator(), environment.getLogger(), environment.getOptions());
                var diagnostics = env.diagnostics();
                var target = env.types().findClass("d.Target").orElseThrow().location();
                facts.put("noErrorsBeforeAnError", diagnostics.hasErrors());
                diagnostics.note(target, "a located note");
                diagnostics.warning(target, "a located warning");
                diagnostics.note(null, "a positionless note");
                diagnostics.warning(null, "a positionless warning");
                diagnostics.error(target, "a located error");
                facts.put("errorsAfterAnError", diagnostics.hasErrors());
                facts.put("foreignLocationIsRejected", rejects(diagnostics, Optional::empty));
            }
            return List.<KSAnnotated>of();
        };
        messages = KspCompiler.compile(probe, KspCompiler.kotlin("Target.kt", KOTLIN))
                .messages();
    }

    private static Object rejects(io.tiko.processor.core.Diagnostics diagnostics, Location foreign) {
        try {
            diagnostics.note(foreign, "never printed");
            return "accepted";
        } catch (IllegalArgumentException e) {
            return "rejected";
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("printed")
    void messagesGoToKspAtTheirSeverityAndPosition(String name, String expectedLine) {
        assertThat(messages.lines())
                .as(messages)
                .anySatisfy(line -> assertThat(line).contains(expectedLine));
    }

    static Stream<Arguments> printed() {
        return Stream.of(
                arguments("noteIsInfoOnTheNodeLine", "i: [ksp] "),
                arguments("locatedNoteNamesFileAndLine", "Target.kt:3: a located note"),
                arguments("locatedWarningNamesFileAndLine", "Target.kt:3: a located warning"),
                arguments("locatedErrorNamesFileAndLine", "Target.kt:3: a located error"),
                arguments("positionlessNoteHasNoFile", "i: [ksp] a positionless note"),
                arguments("positionlessWarningHasNoFile", "w: [ksp] a positionless warning"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recorded")
    void diagnosticsTrackTheirState(String name, Object expected) {
        assertThat(facts).containsEntry(name, expected);
    }

    static Stream<Arguments> recorded() {
        return Stream.of(
                arguments("noErrorsBeforeAnError", false),
                arguments("errorsAfterAnError", true),
                arguments("foreignLocationIsRejected", "rejected"));
    }
}
