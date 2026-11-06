package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the pipeline collects in one javac round and validates in a later one is reported where
 * it is declared. A round that collects only an {@code @EventHandler} defers generation; when the
 * component another processor generates arrives in round 2, validation runs there on the
 * handler collected in round 1, whose method javac has made afresh in the meantime.
 */
class CrossRoundDeclarationsTest {

    private static final JavaFileObject STRAY = JavaFileObjects.forSourceLines(
            "p.Stray",
            "package p;",
            "import io.tiko.annotations.EventHandler;",
            "public class Stray {",
            "    @EventHandler",
            "    public void on(String event) {}",
            "}");

    private static final String LATE_SOURCE = "package p; @io.tiko.annotations.Component public class Late {}";

    static Stream<Arguments> rounds() {
        return Stream.of(
                Arguments.of(
                        "componentInTheSameRound",
                        List.of(new TikoAnnotationProcessor()),
                        List.of(STRAY, JavaFileObjects.forSourceString("p.Late", LATE_SOURCE))),
                Arguments.of(
                        "componentInALaterRound",
                        List.of(new LateComponentGenerator(), new TikoAnnotationProcessor()),
                        List.of(STRAY)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rounds")
    void handlerFromAnEarlierRoundIsReportedOnItsMethod(
            String name, List<Processor> processors, List<JavaFileObject> sources) {
        var compilation = Compiler.javac().withProcessors(processors).compile(sources);

        assertThat(compilation)
                .hadErrorContaining("@EventHandler methods are only wired on @Component classes")
                .inFile(STRAY)
                .onLine(5);
    }

    /** Generates the component {@code p.Late} in the first round, as a second processor would. */
    private static final class LateComponentGenerator extends AbstractProcessor {

        private boolean done;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (done) {
                return false;
            }
            done = true;
            try (Writer writer =
                    processingEnv.getFiler().createSourceFile("p.Late").openWriter()) {
                writer.write(LATE_SOURCE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return false;
        }
    }
}
