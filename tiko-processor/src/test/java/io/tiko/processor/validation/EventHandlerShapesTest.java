package io.tiko.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code @EventHandler} shapes that used to crash the processor or fail as raw javac errors in the
 * generated registry (#746): each either compiles, or is a located error with a suggested fix.
 */
class EventHandlerShapesTest {

    private static final String IMPORTS = """
            import io.tiko.Event;
            import io.tiko.Scope;
            import io.tiko.annotations.Component;
            import io.tiko.annotations.EventHandler;
            """;

    private static final Pattern PACKAGE = Pattern.compile("package ([\\w.]+);");
    private static final Pattern TYPE_NAME = Pattern.compile("(?:class|interface|record) (\\w+)");

    /** Compiles each source under the package it declares (default {@code demo}) and its first type's name. */
    private static Compilation compile(List<String> sources) {
        var files = new ArrayList<JavaFileObject>();
        for (String source : sources) {
            var declared = PACKAGE.matcher(source);
            String packageName = declared.find() ? declared.group(1) : "demo";
            String body = declared.reset().find() ? source.substring(declared.end()) : source;
            var type = TYPE_NAME.matcher(body);
            assertThat(type.find()).as("source declares a type").isTrue();
            files.add(JavaFileObjects.forSourceString(
                    packageName + "." + type.group(1), "package " + packageName + ";\n" + IMPORTS + body));
        }
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(files);
    }

    private static List<String> errorsOf(Compilation compilation) {
        return compilation.errors().stream()
                .map(error -> error.getMessage(Locale.ROOT))
                .toList();
    }

    private static String listener(String handler) {
        return """
                @Component(scope = Scope.SINGLETON)
                public class Listener {
                    %s
                }
                """.formatted(handler);
    }

    private static final String ORDER = "public record OrderPlaced(String id) {}";

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void unsupportedHandlerShapeIsALocatedError(String name, List<String> sources, List<String> fragments) {
        var compilation = compile(sources);

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
                        "primitive event parameter",
                        List.of(listener("@EventHandler public void on(int count) {}")),
                        List.of("@EventHandler 'Listener.on'", "int", "not an event type")),
                Arguments.of(
                        "array event parameter",
                        List.of(ORDER, listener("@EventHandler public void on(OrderPlaced[] batch) {}")),
                        List.of("@EventHandler 'Listener.on'", "demo.OrderPlaced[]", "not an event type")),
                Arguments.of(
                        "type-variable event parameter",
                        List.of(listener("@EventHandler public <T> void on(T event) {}")),
                        List.of("@EventHandler 'Listener.on'", "T", "not an event type")),
                Arguments.of(
                        "two payload parameters",
                        List.of(
                                ORDER,
                                "public record OrderShipped(String id) {}",
                                listener("@EventHandler public void on(OrderPlaced placed, OrderShipped shipped) {}")),
                        List.of("@EventHandler 'Listener.on'", "second parameter", "io.tiko.Event")),
                Arguments.of(
                        "a third parameter",
                        List.of(
                                ORDER,
                                listener(
                                        "@EventHandler public void on(OrderPlaced placed, Event<?> event, String more) {}")),
                        List.of("@EventHandler 'Listener.on'", "at most two parameters")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supported")
    void handlerShapeCompiles(String name, List<String> sources) {
        var compilation = compile(sources);

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    static Stream<Arguments> supported() {
        return Stream.of(
                Arguments.of("async handler that declares a checked exception", List.of(ORDER, listener("""
                                @EventHandler(async = true)
                                    public void on(OrderPlaced placed) throws java.io.IOException {}
                                """))),
                Arguments.of(
                        "sync handler that declares a checked exception",
                        List.of(
                                ORDER,
                                listener("@EventHandler public void on(OrderPlaced placed) throws Exception {}"))),
                Arguments.of(
                        "event type in a package with an uppercase segment",
                        List.of(
                                "package demo.Acme; public record OrderPlaced(String id) {}",
                                listener("@EventHandler public void on(demo.Acme.OrderPlaced placed) {}"))),
                Arguments.of(
                        "event type with a lowercase class name",
                        List.of(
                                "public record placed(String id) {}",
                                listener("@EventHandler public void on(placed event) {}"))),
                Arguments.of("handler on a component with a lowercase class name", List.of(ORDER, """
                                @Component(scope = Scope.SINGLETON)
                                public class listener {
                                    @EventHandler public void on(OrderPlaced placed) {}
                                }
                                """)),
                Arguments.of("handler on a component in a package with an uppercase segment", List.of(ORDER, """
                                package demo.Acme;
                                @Component(scope = Scope.SINGLETON)
                                public class Listener {
                                    @EventHandler public void on(demo.OrderPlaced placed) {}
                                }
                                """)),
                Arguments.of(
                        "event wrapper as the second parameter",
                        List.of(
                                ORDER,
                                listener("@EventHandler public void on(OrderPlaced placed, Event<?> event) {}"))));
    }
}
