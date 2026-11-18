package io.tiko.processor;

import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #685 on javac: {@code UnitOfWork} is a built-in dependency of a component constructor and of a
 * {@code @Produces} method. The container itself stays non-injectable.
 */
class UnitOfWorkInjectionTest {

    private static final String IMPORTS = "import io.tiko.*; import io.tiko.annotations.*;";

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }

    @Test
    void unitOfWorkIsInjectableIntoAComponentConstructor() throws IOException {
        var compilation = compile(source(
                "Routes",
                "@Component(scope = Scope.SINGLETON)",
                "public class Routes {",
                "  @Inject public Routes(UnitOfWork unitOfWork) {}",
                "}"));

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(generated(compilation, "RoutesFactory"))
                .as("the factory hands over a unit-of-work view, not the container")
                .contains("new io.tiko.runtime.ContainerUnitOfWork(container, container.options())");
    }

    @Test
    void unitOfWorkIsInjectableIntoAProducesMethod() throws IOException {
        var compilation = compile(source(
                "Servers",
                "@Component(scope = Scope.SINGLETON)",
                "public class Servers {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public StringBuilder server(UnitOfWork unitOfWork) { return new StringBuilder(); }",
                "}"));

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(generated(compilation, "TikoContainerImpl"))
                .contains("new io.tiko.runtime.ContainerUnitOfWork(this, options)");
    }

    @Test
    void providerOfUnitOfWorkIsInjectable() {
        var compilation = compile(
                source(
                        "LazyRoutes",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class LazyRoutes {",
                        "  @Inject public LazyRoutes(Provider<UnitOfWork> unitOfWork) {}",
                        "}"),
                source(
                        "LazyServers",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class LazyServers {",
                        "  @Produces(scope = Scope.SINGLETON)",
                        "  public StringBuilder server(Provider<UnitOfWork> unitOfWork) { return new StringBuilder(); }",
                        "}"));

        CompilationSubject.assertThat(compilation).succeeded();
    }

    /** One application has one way to open units, so a qualifier names nothing, as for the event bus. */
    @Test
    void namedUnitOfWorkIsNotTheBuiltIn() {
        var compilation = compile(source(
                "Routes",
                "@Component(scope = Scope.SINGLETON)",
                "public class Routes {",
                "  @Inject public Routes(@Named(\"other\") UnitOfWork unitOfWork) {}",
                "}"));

        CompilationSubject.assertThat(compilation)
                .hadErrorContaining("Cannot resolve dependency: io.tiko.UnitOfWork#other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Container", "Provider<Container>"})
    void containerIsStillNotInjectable(String parameterType) {
        var compilation = compile(source(
                "Locator",
                "@Component(scope = Scope.SINGLETON)",
                "public class Locator {",
                "  @Inject public Locator(" + parameterType + " container) {}",
                "}"));

        CompilationSubject.assertThat(compilation).hadErrorContaining("Cannot resolve dependency: io.tiko.Container");
    }
}
