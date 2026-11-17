package io.tiko.processor.validation;

import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import io.tiko.processor.CompileTesting;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #823: a {@code @Pick} names its provider by class, so the cycle check, the scope checks and the
 * shutdown order follow it to that class whether or not the component is exposed as its own class.
 * Each case runs with {@code exposeSelf} left at its default and with {@code exposeSelf = false}.
 */
class PickedComponentGraphTest {

    private static final String IMPORTS = "import io.tiko.Scope; import io.tiko.annotations.*;";

    private static final String DEFAULT_EXPOSURE = "";
    private static final String SELF_HIDDEN = ", exposeSelf = false";

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }

    private static final JavaFileObject PORT = source("Port", "public interface Port {}");

    /** A SINGLETON {@code Consumer} that injects {@code parameter}. */
    private static JavaFileObject consumer(String parameter) {
        return source(
                "Consumer",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer implements AutoCloseable {",
                "  @Inject public Consumer(" + parameter + ") {}",
                "  public void close() {}",
                "}");
    }

    @ParameterizedTest(name = "@Component(...{0})")
    @ValueSource(strings = {DEFAULT_EXPOSURE, SELF_HIDDEN})
    void cycleThroughAPickFailsWithFullPath(String exposure) {
        Compilation c = compile(
                PORT,
                source(
                        "Impl",
                        "@Component(scope = Scope.SINGLETON" + exposure + ")",
                        "public class Impl implements Port {",
                        "  @Inject public Impl(Consumer consumer) {}",
                        "}"),
                consumer("@Pick(Impl.class) Port port"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining("Circular dependency detected: demo.Consumer -> demo.Impl -> demo.Consumer");
    }

    @ParameterizedTest(name = "@Component(...{0})")
    @ValueSource(strings = {DEFAULT_EXPOSURE, SELF_HIDDEN})
    void singletonPickingAPrototypeThatInjectsAConcreteEventBeanFailsCompilation(String exposure) {
        Compilation c = compile(
                PORT,
                source("Ctx", "@Component(scope = Scope.EVENT)", "public class Ctx {}"),
                source(
                        "Mid",
                        "@Component(scope = Scope.PROTOTYPE" + exposure + ")",
                        "public class Mid implements Port {",
                        "  @Inject public Mid(Ctx ctx) {}",
                        "}"),
                consumer("@Pick(Mid.class) Port port"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Ctx must implement an interface");
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Mid'");
    }

    @ParameterizedTest(name = "@Component(...{0})")
    @ValueSource(strings = {DEFAULT_EXPOSURE, SELF_HIDDEN})
    void singletonPickingAnEventBeanByItsSecondInterfaceFailsCompilation(String exposure) {
        Compilation c = compile(
                PORT,
                source("First", "public interface First {}"),
                source(
                        "Impl",
                        "@Component(scope = Scope.EVENT" + exposure + ")",
                        "public class Impl implements First, Port {}"),
                consumer("@Pick(Impl.class) Port port"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining("the generated proxy implements demo.First only, so this injection point"
                        + " cannot be typed by demo.Port");
    }

    @ParameterizedTest(name = "@Component(...{0})")
    @ValueSource(strings = {DEFAULT_EXPOSURE, SELF_HIDDEN})
    void singletonIsClosedBeforeTheSingletonItPicks(String exposure) throws IOException {
        String container = generated(
                compile(
                        PORT,
                        source(
                                "Pool",
                                "@Component(scope = Scope.SINGLETON" + exposure + ")",
                                "public class Pool implements Port, AutoCloseable {",
                                "  public void close() {}",
                                "}"),
                        consumer("@Pick(Pool.class) Port port")),
                "TikoContainerImpl");

        assertThat(container).containsSubsequence("consumer.close()", "pool.close()");
    }
}
