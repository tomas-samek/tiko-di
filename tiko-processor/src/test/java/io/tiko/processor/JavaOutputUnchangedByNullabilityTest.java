package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that the Kotlin nullability rules leave Java output untouched (Review Focus 3): the
 * null-safe caching the container needs for a nullable {@code @Produces} is emitted only when
 * such a provider exists, and Java providers are never nullable. Each row compiles a Java
 * fixture and compares one generated file against a golden copy taken from the generator as
 * it stood before the nullability change.
 */
class JavaOutputUnchangedByNullabilityTest {

    private static final String GOLDEN_DIR = "/golden/nullability/";

    static Stream<Arguments> generatedFiles() {
        return Stream.of(
                Arguments.of("deterministicFixtureContainer", deterministicFixture(), "TikoContainerImpl"),
                Arguments.of("deterministicFixtureRegistry", deterministicFixture(), "EventRegistry"),
                Arguments.of("producesFixtureContainer", producesFixture(), "TikoContainerImpl"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedFiles")
    void generatedJavaMatchesGolden(String name, JavaFileObject[] sources, String generatedFragment)
            throws IOException {
        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(generatedSource(compilation, generatedFragment)).isEqualTo(golden(name));
    }

    /** The fixture of {@link DeterministicGenerationTest#sourceOrderPermutationProducesIdenticalOutput}. */
    private static JavaFileObject[] deterministicFixture() {
        return new JavaFileObject[] {
            JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}"),
            handler("Alpha"),
            handler("Mike"),
            JavaFileObjects.forSourceLines(
                    "demo.Zulu",
                    "package demo;",
                    "import io.tiko.Scope;",
                    "import io.tiko.annotations.Component;",
                    "@Component(scope = Scope.SINGLETON)",
                    "public class Zulu {}")
        };
    }

    /** SINGLETON and EVENT {@code @Produces} slots — the caching the nullability change touches. */
    private static JavaFileObject[] producesFixture() {
        return new JavaFileObject[] {
            JavaFileObjects.forSourceLines("demo.Clock", "package demo;", "public interface Clock {}"),
            JavaFileObjects.forSourceLines("demo.RequestId", "package demo;", "public interface RequestId {}"),
            JavaFileObjects.forSourceLines(
                    "demo.Producers",
                    "package demo;",
                    "import io.tiko.Scope;",
                    "import io.tiko.annotations.Component;",
                    "import io.tiko.annotations.Produces;",
                    "@Component(scope = Scope.SINGLETON)",
                    "public class Producers {",
                    "  @Produces(scope = Scope.SINGLETON)",
                    "  public Clock clock() { return new Clock() {}; }",
                    "  @Produces(scope = Scope.EVENT)",
                    "  public RequestId requestId() { return new RequestId() {}; }",
                    "}"),
            JavaFileObjects.forSourceLines(
                    "demo.Service",
                    "package demo;",
                    "import io.tiko.Scope;",
                    "import io.tiko.annotations.Component;",
                    "@Component(scope = Scope.SINGLETON)",
                    "public class Service {",
                    "  public Service(Clock clock, RequestId requestId) {}",
                    "}")
        };
    }

    private static JavaFileObject handler(String name) {
        return JavaFileObjects.forSourceLines(
                "demo." + name,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + name + " {",
                "  @EventHandler",
                "  public void onPing(PingEvent event) {}",
                "}");
    }

    private static String generatedSource(Compilation compilation, String nameFragment) throws IOException {
        var file = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String golden(String name) {
        try (var in = JavaOutputUnchangedByNullabilityTest.class.getResourceAsStream(GOLDEN_DIR + name + ".java.txt")) {
            if (in == null) {
                throw new AssertionError("missing golden file " + GOLDEN_DIR + name + ".java.txt");
            }
            // core.autocrlf may check the golden out with CRLF; the generator writes LF.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
