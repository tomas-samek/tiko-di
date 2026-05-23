package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.processor.model.WiringError;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of {@link WiringErrorsWriter} via google-compile-testing — the
 * same harness {@code TopologyWriterTest} uses. Asserts the sibling artifact ships
 * on a clean build with an empty list, and that a broken-wiring fixture populates a
 * structured {@code MISSING_DEPENDENCY} entry. A small render-only test pins the
 * field ordering inside each error so format regressions surface immediately.
 */
class WiringErrorsWriterTest {

    @Test
    void cleanBuildEmitsEmptyErrorsArray() throws IOException {
        JavaFileObject service = JavaFileObjects.forSourceLines(
                "io.example.Plain",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Plain { @Inject public Plain() {} }");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(service);
        assertThat(c).succeeded();

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/wiring-errors.json");
        assertThat(fileOpt).isPresent();

        String json;
        try (var r = new InputStreamReader(fileOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(json).contains("\"errors\"").contains("[]");
    }

    @Test
    void missingDependencyPopulatesStructuredEntry() throws IOException {
        // Service depends on an unregistered type — DependencyGraphValidator fires
        // missingDependency, which now records a WiringError tagged MISSING_DEPENDENCY.
        JavaFileObject service = JavaFileObjects.forSourceLines(
                "io.example.OrderService",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class OrderService {",
                "  @Inject public OrderService(io.example.MissingRepo repo) {}",
                "}");
        // MissingRepo exists as a plain class but is NOT a @Component — so the resolver
        // can't find a provider for it, and we get a clean "Cannot resolve dependency".
        JavaFileObject repo = JavaFileObjects.forSourceLines(
                "io.example.MissingRepo", "package io.example;", "public class MissingRepo {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(service, repo);
        // Compilation fails on purpose — the validator reports an ERROR diagnostic.
        assertThat(c).failed();

        // The wiring-errors.json sibling must still be present (try/finally in the
        // processor guarantees emission even when validation aborts the round).
        // compile-testing.Compilation.generatedFile() refuses to expose files on a failed
        // compilation, but the field is populated regardless — reach in by reflection so
        // this round-trip stays end-to-end.
        var fileOpt =
                generatedFileOnFailedCompilation(c, StandardLocation.CLASS_OUTPUT, "META-INF/tiko/wiring-errors.json");
        assertThat(fileOpt).isPresent();

        String json;
        try (var r = new InputStreamReader(fileOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(json)
                .contains("\"kind\": \"MISSING_DEPENDENCY\"")
                .contains("\"componentFqn\": \"io.example.OrderService\"")
                .contains("\"message\":")
                .contains("io.example.MissingRepo");
    }

    /**
     * Reflective bypass for {@link Compilation#generatedFile(javax.tools.JavaFileManager.Location, String)}
     * — that helper throws when the compilation failed, but the underlying
     * {@code generatedFiles} field is populated either way. We only need it for the
     * one negative-path test that asserts {@code wiring-errors.json} ships on a
     * deliberately-broken build.
     */
    private static Optional<JavaFileObject> generatedFileOnFailedCompilation(
            Compilation c, StandardLocation location, String path) {
        try {
            Field f = Compilation.class.getDeclaredField("generatedFiles");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            ImmutableList<JavaFileObject> all = (ImmutableList<JavaFileObject>) f.get(c);
            String expectedTail = location.getName() + "/" + path;
            return all.stream()
                    .filter(fo -> fo.toUri().getPath().endsWith(expectedTail))
                    .findFirst();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot reach generatedFiles via reflection: " + e, e);
        }
    }

    @Test
    void renderEmitsFieldsInDocumentedOrder() {
        var error = new WiringError(
                "MISSING_DEPENDENCY",
                "src/main/java/X.java",
                17,
                "example.X",
                "No component provides Y",
                "Add @Component to Y");

        String json = new WiringErrorsWriter(List.of(error)).render();
        int kindIdx = json.indexOf("\"kind\"");
        int sourceFileIdx = json.indexOf("\"sourceFile\"");
        int lineIdx = json.indexOf("\"line\"");
        int componentFqnIdx = json.indexOf("\"componentFqn\"");
        int messageIdx = json.indexOf("\"message\"");
        int suggestedFixIdx = json.indexOf("\"suggestedFix\"");

        // Field order inside the error entry is part of the contract — see
        // docs/topology-schema.md "Sibling artifact" section.
        assertThat(kindIdx).isLessThan(sourceFileIdx);
        assertThat(sourceFileIdx).isLessThan(lineIdx);
        assertThat(lineIdx).isLessThan(componentFqnIdx);
        assertThat(componentFqnIdx).isLessThan(messageIdx);
        assertThat(messageIdx).isLessThan(suggestedFixIdx);

        // line is an integer literal, not a JSON string.
        assertThat(json).contains("\"line\": 17");
    }

    @Test
    void renderEmitsNullsForUnknownFields() {
        var error = new WiringError("OTHER", null, 0, null, "boom", null);
        String json = new WiringErrorsWriter(List.of(error)).render();
        assertThat(json)
                .contains("\"sourceFile\": null")
                .contains("\"line\": 0")
                .contains("\"componentFqn\": null")
                .contains("\"suggestedFix\": null");
    }

    @Test
    void emptyErrorsListRenderRoundtrip() {
        String json = new WiringErrorsWriter(List.of()).render();
        // Compact assertion that survives the pretty-printer's whitespace.
        assertThat(json.replaceAll("\\s+", "")).isEqualTo("{\"errors\":[]}");
    }
}
