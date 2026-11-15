package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import org.junit.jupiter.api.Test;

/**
 * #729 on the KSP frontend, and at run time: a Kotlin handler that states {@code eventType}
 * receives the events of exactly that class, and one whose event type is an interface is a
 * located error.
 */
class KspEventTypeRoutingTest {

    private static final String[] SHAPES = {
        "package app",
        "",
        "import io.tiko.EventBus",
        "import io.tiko.Scope",
        "import io.tiko.annotations.Component",
        "import io.tiko.annotations.EventHandler",
        "",
        "interface Shape",
        "class Circle : Shape",
        "class Square : Shape",
        "",
        "object Seen { @JvmField val items = java.util.concurrent.CopyOnWriteArrayList<String>() }",
        ""
    };

    private static String program(String... declarations) {
        return String.join("\n", SHAPES) + "\n" + String.join("\n", declarations);
    }

    @Test
    void handlerThatStatesEventTypeReceivesExactlyThatClass() throws Exception {
        var source = KspCompiler.kotlin(
                "Shapes.kt",
                program(
                        "@Component(scope = Scope.SINGLETON)",
                        "class Listener {",
                        "    @EventHandler(eventType = Circle::class)",
                        "    fun on(shape: Shape) { Seen.items.add(shape.javaClass.simpleName) }",
                        "}",
                        "",
                        "@Component(scope = Scope.SINGLETON)",
                        "class Publisher(private val bus: EventBus) {",
                        "    fun fire() { bus.publish(Circle()); bus.publish(Square()) }",
                        "}"));

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var publisher = program.get("app.Publisher");
            publisher.getClass().getMethod("fire").invoke(publisher);

            assertThat(program.staticList("app.Seen", "items")).containsExactly("Circle");
        }
    }

    @Test
    void handlerWhoseEventTypeIsAnInterfaceIsALocatedError() {
        var source = KspCompiler.kotlin(
                "Shapes.kt",
                program(
                        "@Component(scope = Scope.SINGLETON)",
                        "class Listener {",
                        "    @EventHandler fun on(shape: Shape) {}",
                        "}"));

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("Shapes.kt:")
                .contains("@EventHandler 'Listener.on' would never be invoked")
                .contains("app.Shape is an interface")
                .contains("Suggested fixes:")
                .doesNotContain("INTERNAL_ERROR");
    }
}
