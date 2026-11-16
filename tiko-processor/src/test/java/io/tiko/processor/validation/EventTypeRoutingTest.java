package io.tiko.processor.validation;

import static io.tiko.processor.validation.ShapeTesting.assertLocatedError;
import static io.tiko.processor.validation.ShapeTesting.compile;
import static io.tiko.processor.validation.ShapeTesting.errorsOf;
import static io.tiko.processor.validation.ShapeTesting.listener;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.io.IOException;
import java.util.List;
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
            import io.tiko.Event;
            import io.tiko.Scope;
            import io.tiko.annotations.Component;
            import io.tiko.annotations.EventHandler;
            """;

    private static final String SHAPE = "public interface Shape {}";
    private static final String CIRCLE = "public record Circle(int radius) implements Shape {}";
    private static final String SQUARE = "public record Square(int side) implements Shape {}";

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
        var compilation = compile(IMPORTS, List.of(SHAPE, CIRCLE, SQUARE, listener("""
                        @EventHandler(eventType = Circle.class)
                            public void onCircle(Shape shape) {}

                            @EventHandler(eventType = Square.class)
                            public void onSquare(Shape shape, Event<?> event) {}
                        """)));

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(registryOf(compilation))
                .contains("eventBus.subscribe(Circle.class,")
                .contains("eventBus.subscribe(Square.class,")
                .doesNotContain("eventBus.subscribe(Shape.class,");
    }

    @Test
    void handlerOfAConcreteTypeNeedsNoEventType() throws IOException {
        var compilation =
                compile(IMPORTS, List.of(SHAPE, CIRCLE, listener("@EventHandler public void on(Circle circle) {}")));

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(registryOf(compilation)).contains("eventBus.subscribe(Circle.class,");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void handlerThatCouldNeverBeInvokedOrMisusesEventTypeIsALocatedError(
            String name, List<String> sources, List<String> fragments) {
        assertLocatedError(compile(IMPORTS, sources), fragments);
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
