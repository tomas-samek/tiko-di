package io.tiko.processor.core.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import io.tiko.processor.core.ksp.RecordingCodeGenerator.Request;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code writeResource} goes through {@code createNewFileByPath} with the path as given (no
 * extension appended), aggregating over its originating declarations' files, and writes UTF-8.
 */
class KspCodeOutputTest {

    private static final String PATH = "META-INF/facts/hello.txt";
    private static final String CONTENT = "héllo, ünïcode\n";

    private static final List<Request> requests = new ArrayList<>();
    private static KspResult result;

    @BeforeAll
    static void compile() {
        SymbolProcessorProvider writer = environment -> {
            var written = new boolean[1];
            return resolver -> {
                if (!written[0]) {
                    written[0] = true;
                    var env = KspProcessingEnv.of(
                            resolver,
                            environment.getCodeGenerator(),
                            environment.getLogger(),
                            environment.getOptions());
                    var origin = env.types().findClass("o.Origin").orElseThrow();
                    try {
                        env.output().writeResource(PATH, CONTENT, List.of(origin));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return List.<KSAnnotated>of();
            };
        };
        result = KspCompiler.compile(
                RecordingCodeGenerator.recording(writer, requests),
                KspCompiler.kotlin("Origin.kt", "package o", "", "class Origin"));
    }

    @Test
    void resourceIsRequestedByItsPathAsAggregatingOverItsOrigin() {
        assertThat(requests).containsExactly(new Request(PATH, true, List.of("Origin.kt")));
    }

    @Test
    void resourceContentIsWrittenAsUtf8() {
        assertThat(result.generatedResource(PATH)).hasValue(CONTENT);
    }
}
