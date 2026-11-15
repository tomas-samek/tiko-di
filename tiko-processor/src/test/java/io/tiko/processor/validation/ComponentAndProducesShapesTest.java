package io.tiko.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Component and {@code @Produces} shapes that used to fail as raw javac errors in generated code
 * (#746): each either compiles, or is a located error with a suggested fix.
 */
class ComponentAndProducesShapesTest {

    private static final String IMPORTS = """
            import io.tiko.Scope;
            import io.tiko.annotations.Component;
            import io.tiko.annotations.Inject;
            import io.tiko.annotations.Produces;
            """;

    /** A source file named by its fully qualified top-level class; a name without a dot is in the unnamed package. */
    private static JavaFileObject source(String fqn, String body) {
        int dot = fqn.lastIndexOf('.');
        String packageLine = dot < 0 ? "" : "package " + fqn.substring(0, dot) + ";\n";
        return JavaFileObjects.forSourceString(fqn, packageLine + IMPORTS + body);
    }

    /** Generates {@code demo.Later} in the first round, as another annotation processor would. */
    static final class LaterGenerator extends AbstractProcessor {
        private boolean generated;

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
            if (!generated) {
                generated = true;
                try (Writer writer =
                        processingEnv.getFiler().createSourceFile("demo.Later").openWriter()) {
                    writer.write("package demo; public interface Later {}");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return false;
        }
    }

    private static Compilation compile(boolean withLaterGenerator, List<JavaFileObject> sources) {
        var processors = new ArrayList<Processor>();
        if (withLaterGenerator) {
            processors.add(new LaterGenerator());
        }
        processors.add(new TikoAnnotationProcessor());
        return Compiler.javac().withProcessors(processors).compile(sources);
    }

    private static List<String> errorsOf(Compilation compilation) {
        return compilation.errors().stream()
                .map(error -> error.getMessage(Locale.ROOT))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void unsupportedShapeIsALocatedError(
            String name, boolean withLaterGenerator, List<JavaFileObject> sources, List<String> fragments) {
        var compilation = compile(withLaterGenerator, sources);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errorsOf(compilation))
                .as("no processor crash")
                .noneMatch(message -> message.contains("Tiko DI processing failed"));
        assertThat(errorsOf(compilation))
                .anySatisfy(message -> assertThat(message).contains(fragments).contains("Suggested fixes:"));
        assertThat(compilation.errors())
                .as("no error in generated code")
                .noneMatch(error ->
                        error.getSource() != null && error.getSource().getName().contains("io/tiko/generated"));
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of(
                        "package-private component",
                        false,
                        List.of(source("demo.Hidden", """
                                @Component(scope = Scope.SINGLETON)
                                class Hidden {
                                    public Hidden() {}
                                }
                                """)),
                        List.of("@Component demo.Hidden must be public")),
                Arguments.of(
                        "package-private component with its default constructor",
                        false,
                        List.of(source("demo.Hidden", """
                                @Component(scope = Scope.SINGLETON)
                                class Hidden {}
                                """)),
                        List.of("@Component demo.Hidden must be public")),
                Arguments.of(
                        "component nested in a package-private class",
                        false,
                        List.of(source("demo.Outer", """
                                class Outer {
                                    @Component(scope = Scope.SINGLETON)
                                    public static class Inner {}
                                }
                                """)),
                        List.of("@Component demo.Outer.Inner must be public", "demo.Outer")),
                Arguments.of(
                        "component in the unnamed package",
                        false,
                        List.of(source("Rootless", """
                                @Component(scope = Scope.SINGLETON)
                                public class Rootless {}
                                """)),
                        List.of("@Component Rootless", "unnamed package")),
                Arguments.of(
                        "component with type parameters",
                        false,
                        List.of(source("demo.Box", """
                                        @Component(scope = Scope.SINGLETON)
                                        public class Box<T> {}
                                        """), source("demo.Consumer", """
                                        @Component(scope = Scope.SINGLETON)
                                        public class Consumer {
                                            @Inject public Consumer(Box<String> box) {}
                                        }
                                        """)),
                        List.of("@Component demo.Box", "type parameters")),
                Arguments.of(
                        "component that implements a package-private interface",
                        false,
                        List.of(source("demo.Impl", """
                                interface Api {}
                                @Component(scope = Scope.SINGLETON)
                                public class Impl implements Api {}
                                """)),
                        List.of("demo.Impl is routed as demo.Api, which is not public", "Make demo.Api public")),
                Arguments.of(
                        "@Produces method with type parameters",
                        false,
                        List.of(source("demo.Cfg", """
                                @Component(scope = Scope.SINGLETON)
                                public class Cfg {
                                    @Produces public <T> T anything() { return null; }
                                }
                                """)),
                        List.of("@Produces method 'demo.Cfg.anything'", "type parameters")),
                Arguments.of(
                        "overloaded @Produces methods",
                        false,
                        List.of(source("demo.Cfg", """
                                @Component(scope = Scope.SINGLETON)
                                public class Cfg {
                                    public static class Thing {}
                                    @Produces(scope = Scope.SINGLETON)
                                    public Thing thing() { return new Thing(); }
                                    @Produces(scope = Scope.SINGLETON, name = "second")
                                    public Thing thing(Cfg self) { return new Thing(); }
                                }
                                """)),
                        List.of("@Produces methods named 'thing' in demo.Cfg", "Rename")),
                Arguments.of(
                        "parameter of a type another processor generates in this build",
                        true,
                        List.of(source("demo.Consumer", """
                                @Component(scope = Scope.SINGLETON)
                                public class Consumer {
                                    @Inject public Consumer(Later later) {}
                                }
                                """)),
                        List.of("Consumer", "'later'", "does not resolve", "another annotation processor")),
                Arguments.of(
                        "component that implements a type another processor generates in this build",
                        true,
                        List.of(source("demo.LaterImpl", """
                                @Component(scope = Scope.SINGLETON)
                                public class LaterImpl implements Later {}
                                """)),
                        List.of("demo.LaterImpl implements", "does not resolve", "another annotation processor")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supported")
    void shapeCompiles(String name, List<JavaFileObject> sources) {
        var compilation = compile(false, sources);

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    static Stream<Arguments> supported() {
        return Stream.of(
                Arguments.of("static @Produces in a nested component", List.of(source("demo.Outer", """
                                public class Outer {
                                    public static class Thing {}
                                    @Component(scope = Scope.SINGLETON)
                                    public static class Cfg {
                                        @Produces(scope = Scope.SINGLETON)
                                        public static Thing thing() { return new Thing(); }
                                    }
                                }
                                """))),
                Arguments.of("instance @Produces in a nested component", List.of(source("demo.Outer", """
                                public class Outer {
                                    public static class Thing {}
                                    @Component(scope = Scope.SINGLETON)
                                    public static class Cfg {
                                        @Produces(scope = Scope.SINGLETON)
                                        public Thing thing() { return new Thing(); }
                                    }
                                }
                                """))),
                Arguments.of(
                        "component whose only non-public interface is left out by expose",
                        List.of(source("demo.Impl", """
                                interface Api {}
                                @Component(scope = Scope.SINGLETON, expose = {Impl.class})
                                public class Impl implements Api {}
                                """))));
    }
}
