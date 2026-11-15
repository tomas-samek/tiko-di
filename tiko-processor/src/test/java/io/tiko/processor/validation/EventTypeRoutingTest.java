package io.tiko.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An event is routed by its exact class (#729). {@code @EventHandler(eventType = X.class)} names
 * the class a handler subscribes to when its parameter is a supertype, and a handler whose event
 * type no event can have, an interface or an abstract class, is a compile error: it could never
 * be invoked.
 */
class EventTypeRoutingTest {

    private static final String IMPORTS = """
            package demo;
            import io.tiko.Event;
            import io.tiko.Scope;
            import io.tiko.annotations.Component;
            import io.tiko.annotations.EventHandler;
            """;

    private static final Pattern TYPE_NAME = Pattern.compile("(?:class|interface|record) (\\w+)");

    private static final String SHAPE = "public interface Shape {}";
    private static final String CIRCLE = "public record Circle(int radius) implements Shape {}";
    private static final String SQUARE = "public record Square(int side) implements Shape {}";

    private static Compilation compile(String... sources) {
        var files = new ArrayList<JavaFileObject>();
        for (String source : sources) {
            var matcher = TYPE_NAME.matcher(source);
            assertThat(matcher.find()).as("source declares a type").isTrue();
            files.add(JavaFileObjects.forSourceString("demo." + matcher.group(1), IMPORTS + source));
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

    private static String registryOf(Compilation compilation) throws IOException {
        for (JavaFileObject file : compilation.generatedSourceFiles()) {
            if (file.getName().contains("EventRegistry_")) {
                return file.getCharContent(true).toString().replaceAll("\\s+", " ");
            }
        }
        throw new AssertionError("no EventRegistry was generated");
    }

    @Test
    void eventTypeNamesTheClassTheHandlerSubscribesTo() throws IOException {
        var compilation = compile(SHAPE, CIRCLE, SQUARE, listener("""
                        @EventHandler(eventType = Circle.class)
                            public void onCircle(Shape shape) {}

                            @EventHandler(eventType = Square.class)
                            public void onSquare(Shape shape, Event<?> event) {}
                        """));

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(registryOf(compilation))
                .contains("eventBus.subscribe(Circle.class,")
                .contains("eventBus.subscribe(Square.class,")
                .doesNotContain("eventBus.subscribe(Shape.class,");
    }

    @Test
    void handlerOfAConcreteTypeNeedsNoEventType() throws IOException {
        var compilation = compile(SHAPE, CIRCLE, listener("@EventHandler public void on(Circle circle) {}"));

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(registryOf(compilation)).contains("eventBus.subscribe(Circle.class,");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void handlerThatCouldNeverBeInvokedOrMisusesEventTypeIsALocatedError(
            String name, List<String> sources, List<String> fragments) {
        var compilation = compile(sources.toArray(String[]::new));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
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
                        "interface parameter without eventType",
                        List.of(SHAPE, CIRCLE, listener("@EventHandler public void on(Shape shape) {}")),
                        List.of(
                                "@EventHandler 'Listener.on'",
                                "demo.Shape",
                                "interface",
                                "never be invoked",
                                "eventType = ")),
                Arguments.of(
                        "abstract class parameter without eventType",
                        List.of(
                                "public abstract class Base {}",
                                listener("@EventHandler public void on(Base base) {}")),
                        List.of("@EventHandler 'Listener.on'", "demo.Base", "abstract class", "never be invoked")),
                Arguments.of(
                        "eventType that is itself an interface",
                        List.of(
                                SHAPE,
                                "public interface Round extends Shape {}",
                                listener("@EventHandler(eventType = Round.class) public void on(Shape shape) {}")),
                        List.of("@EventHandler 'Listener.on'", "demo.Round", "interface", "never be invoked")),
                Arguments.of(
                        "eventType that is not a subtype of the parameter",
                        List.of(
                                SHAPE,
                                CIRCLE,
                                "public record Order(String id) {}",
                                listener("@EventHandler(eventType = Order.class) public void on(Circle circle) {}")),
                        List.of("@EventHandler 'Listener.on'", "eventType demo.Order", "is not a demo.Circle")),
                Arguments.of(
                        "wrapper typed for another event than the one subscribed to",
                        List.of(
                                SHAPE,
                                CIRCLE,
                                listener("@EventHandler(eventType = Circle.class)"
                                        + " public void on(Shape shape, Event<Shape> event) {}")),
                        List.of("@EventHandler 'Listener.on'", "Event<demo.Shape>", "Event<?>")));
    }
}
