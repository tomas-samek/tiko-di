package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Reads {@code META-INF/tiko/wiring-errors.json} from a KSP run, where each error carries its kind. */
final class WiringErrorsJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WiringErrorsJson() {}

    /**
     * The run failed, the compiler output contains every fragment, and one error in
     * {@code wiring-errors.json} has {@code kind} and a problem text containing the first fragment
     * (the problem fragment; later ones may sit in the suggested fixes).
     */
    static void assertRejected(KspResult result, String kind, List<String> fragments) {
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages()).contains(fragments);
        assertThat(errors(result)).as("wiring-errors.json").anySatisfy(error -> {
            assertThat(error.get("kind")).isEqualTo(kind);
            assertThat(String.valueOf(error.get("message"))).contains(fragments.get(0));
        });
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> errors(KspResult result) {
        var json = result.generatedResource("META-INF/tiko/wiring-errors.json")
                .orElseThrow(() -> new AssertionError("no wiring-errors.json\n" + result.messages()));
        try {
            return (List<Map<String, Object>>) JSON.readValue(json, Map.class).get("errors");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
