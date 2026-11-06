package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.processor.core.ksp.KspCompiler;
import org.junit.jupiter.api.Test;

/**
 * A collection-time error does not cut processing short on KSP: KSP then calls {@code onError()}
 * instead of {@code finish()}, and the entry still finishes, so the rest of the graph is validated
 * and {@code wiring-errors.json} is written, as on javac.
 */
class KspCollectionErrorTest {

    private static final String COLLECTION_ERROR =
            "must have an @Inject-annotated constructor or a single public constructor";
    private static final String VALIDATION_ERROR = "Cannot resolve dependency: app.Gateway";

    @Test
    void collectionErrorStillValidatesAndWritesWiringErrors() {
        var source = KspCompiler.kotlin(
                "Graph.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "interface Gateway",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Ambiguous {",
                "    constructor(name: String)",
                "    constructor()",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Consumer(val gateway: Gateway)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages()).contains(COLLECTION_ERROR).contains(VALIDATION_ERROR);
        assertThat(result.generatedResource("META-INF/tiko/wiring-errors.json"))
                .hasValueSatisfying(json -> assertThat(json)
                        .contains("\"kind\": \"MISSING_DEPENDENCY\"")
                        .contains("\"componentFqn\": \"app.Consumer\""));
    }

    /** The javac baseline the KSP behaviour matches: both errors are reported. */
    @Test
    void javacReportsCollectionAndValidationErrorsAlike() {
        var gateway = JavaFileObjects.forSourceLines("app.Gateway", "package app;", "public interface Gateway {}");
        var ambiguous = JavaFileObjects.forSourceLines(
                "app.Ambiguous",
                "package app;",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "public class Ambiguous {",
                "    public Ambiguous(String name) {}",
                "    public Ambiguous() {}",
                "}");
        var consumer = JavaFileObjects.forSourceLines(
                "app.Consumer",
                "package app;",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "public class Consumer {",
                "    public Consumer(Gateway gateway) {}",
                "}");

        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(gateway, ambiguous, consumer);

        CompilationSubject.assertThat(compilation).hadErrorContaining(COLLECTION_ERROR);
        CompilationSubject.assertThat(compilation).hadErrorContaining(VALIDATION_ERROR);
    }
}
