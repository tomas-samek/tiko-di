package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * javac's {@code -Xlint:processing} reports "No processor claimed any of these annotations" for
 * every annotation no processor supports and returns {@code true} for, which fails {@code -Werror}
 * builds. The processor claims all Tiko annotations in the round it generates, and the
 * {@code @Generated} on its output in the round after — but never another processor's output alone.
 */
class AnnotationClaimTest {

    private static JavaFileObject bean(String name) {
        return JavaFileObjects.forSourceLines(
                "demo." + name,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + name + " {}");
    }

    private static Compilation compileStrict(JavaFileObject... sources) {
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Xlint:processing", "-Werror")
                .compile(sources);
    }

    private static boolean anyDiagnosticContains(Compilation c, String fragment) {
        return c.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains(fragment));
    }

    private static JavaFileObject everyLifecycleAnnotation() {
        return JavaFileObjects.forSourceLines(
                "demo.Rich",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Rich {",
                "  public record Ping(String id) {}",
                "  public record Pong(String id) {}",
                "  public static class Helper {}",
                "  @Inject public Rich(@Named(\"main\") Helper helper) {}",
                "  @PostConstruct public void init() {}",
                "  @PreDestroy public void close() {}",
                "  @EventHandler @EventTrigger(eventName = \"Pong\")",
                "  public Pong onPing(Ping p) { return new Pong(p.id()); }",
                "  @Produces(scope = Scope.SINGLETON, name = \"main\")",
                "  public static Helper helper() { return new Helper(); }",
                "}");
    }

    static Stream<Arguments> tikoApps() {
        return Stream.of(
                Arguments.of("bare component (claims @Generated on output)", bean("Alpha")),
                Arguments.of("inject, named, lifecycle, trigger, produces", everyLifecycleAnnotation()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tikoApps")
    void tikoAppCompilesUnderProcessingLintWithWerror(String name, JavaFileObject source) {
        var compilation = compileStrict(source);

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(anyDiagnosticContains(compilation, "No processor claimed")).isFalse();
    }

    @Test
    void foreignGeneratedSourceAloneDoesNotWakeTheProcessor() {
        var foreign = JavaFileObjects.forSourceLines(
                "demo.Foreign",
                "package demo;",
                "@javax.annotation.processing.Generated(\"some.other.Processor\")",
                "public class Foreign {}");

        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(foreign);

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFiles()).isEmpty();
        assertThat(anyDiagnosticContains(compilation, "No components, factories, or configurations found"))
                .as("a project without Tiko annotations must not get the empty-container warning")
                .isFalse();
    }

    @Test
    void tikoAnnotatedSourceInLaterRoundIsStillRejected() {
        var compilation = Compiler.javac()
                .withProcessors(new LateComponentEmitter(), new TikoAnnotationProcessor())
                .compile(bean("Alpha"));

        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation)
                .hadErrorContaining("Tiko-annotated sources appeared in a later annotation-processing round");
    }

    /**
     * Emits a {@code @Component} source in round one, so it reaches Tiko only in round two. Must
     * be registered before Tiko's processor, which claims {@code @Component} in round one.
     */
    private static final class LateComponentEmitter extends AbstractProcessor {

        private boolean emitted;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("io.tiko.annotations.Component");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (emitted || roundEnv.processingOver()) {
                return false;
            }
            emitted = true;
            try (Writer w =
                    processingEnv.getFiler().createSourceFile("demo.Late").openWriter()) {
                w.write("""
                        package demo;
                        @io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)
                        public class Late {}
                        """);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
            }
            return false;
        }
    }
}
