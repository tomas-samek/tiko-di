package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * {@code EventBus} is a built-in injectable dependency (#314): a {@code @Component} that
 * needs to publish imperatively can take {@code EventBus} as a constructor parameter
 * instead of being a hand-constructed plain class. The generated factory resolves it from
 * the container ({@code container.getEventBus()}); the dependency graph treats it as a
 * built-in, not a missing bean. {@code Container} is deliberately NOT injectable — that
 * would be service location; {@code EventBus} is a legitimate collaborator interface.
 */
class EventBusInjectionTest {

    @Test
    void eventBusIsInjectableIntoAComponentConstructor() throws IOException {
        JavaFileObject publisher = JavaFileObjects.forSourceLines(
                "demo.Publisher",
                "package demo;",
                "import io.tiko.EventBus;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Publisher {",
                "  private final EventBus bus;",
                "  @Inject public Publisher(EventBus bus) { this.bus = bus; }",
                "  public void emit(Object event) { bus.publish(event); }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(publisher);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "PublisherFactory"))
                .as("the generated factory must resolve EventBus from the container")
                .contains("getEventBus()");
    }

    @Test
    void eventBusIsInjectableIntoAProducesFactoryMethod() {
        JavaFileObject sink = JavaFileObjects.forSourceLines(
                "demo.Sinks",
                "package demo;",
                "import io.tiko.EventBus;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Sinks {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public String greeting(EventBus bus) { return bus.toString(); }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sink);

        CompilationSubject.assertThat(c).succeeded();
    }

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        JavaFileObject file = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
