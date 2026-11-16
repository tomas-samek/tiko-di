package io.tiko.processor;

import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import java.io.IOException;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the generated container tears down, and in which order, where the component graph is not a
 * plain SINGLETON chain: lifecycle hooks a component inherits (#742), a SINGLETON another one
 * reaches through a PROTOTYPE (#739), and an {@code ErrorHandler} that throws while it is told
 * about a failure that is about to be rethrown (#736).
 */
class TeardownGenerationTest {

    private static final String IMPORTS = "import io.tiko.Scope; import io.tiko.annotations.*;";

    private static final JavaFileObject BASE = source(
            "Base",
            "@Component(scope = Scope.EVENT)",
            "public class Base {",
            "  @PostConstruct public void baseInit() {}",
            "  @PreDestroy public void baseBye() {}",
            "  @PreDestroy public void switchedOff() {}",
            "  public void baseBye(String notAHook) {}",
            "}");

    private static final JavaFileObject DERIVED = source(
            "Derived",
            "@Component(scope = Scope.EVENT)",
            "public class Derived extends Base {",
            "  @PostConstruct public void derivedInit() {}",
            "  @PreDestroy public void derivedBye() {}",
            "  @Override public void switchedOff() {}",
            "}");

    @Test
    void eventSubclassIsDispatchedBeforeItsSuperclassAndRunsItsOwnHookThenTheInheritedOne() throws IOException {
        String container = generated(compile(BASE, DERIVED), "TikoContainerImpl");

        assertThat(container.indexOf("instanceof Derived"))
                .as("an instanceof chain must test the subclass before the superclass it also matches")
                .isPositive()
                .isLessThan(container.indexOf("instanceof Base"));
        assertThat(container).containsSubsequence("__derived.derivedBye()", "__derived.baseBye()", "__base.baseBye()");
    }

    @Test
    void inheritedHookAnUnannotatedOverrideReplacesIsNotRun() throws IOException {
        String container = generated(compile(BASE, DERIVED), "TikoContainerImpl");

        assertThat(container).contains("__base.switchedOff()").doesNotContain("__derived.switchedOff()");
    }

    @Test
    void inheritedPostConstructRunsBeforeTheSubclassOne() throws IOException {
        String factory = generated(compile(BASE, DERIVED), "DerivedFactory");

        assertThat(factory).containsSubsequence("instance.baseInit()", "instance.derivedInit()");
    }

    static Stream<Arguments> prototypesBetweenTwoSingletons() {
        JavaFileObject pool = source(
                "Pool",
                "@Component(scope = Scope.SINGLETON)",
                "public class Pool implements AutoCloseable {",
                "  public void close() {}",
                "}");
        return Stream.of(
                Arguments.of("PROTOTYPE @Component", new JavaFileObject[] {
                    pool,
                    source(
                            "Helper",
                            "@Component",
                            "public class Helper {",
                            "  @Inject public Helper(Pool pool) {}",
                            "}"),
                    source(
                            "AService",
                            "@Component(scope = Scope.SINGLETON)",
                            "public class AService implements AutoCloseable {",
                            "  @Inject public AService(Helper helper, Helper again) {}",
                            "  public void close() {}",
                            "}")
                }),
                Arguments.of("PROTOTYPE @Produces", new JavaFileObject[] {
                    pool,
                    source("Thing", "public class Thing {}"),
                    source(
                            "Things",
                            "@Component",
                            "public class Things {",
                            "  @Produces public static Thing thing(Pool pool) { return new Thing(); }",
                            "}"),
                    source(
                            "AService",
                            "@Component(scope = Scope.SINGLETON)",
                            "public class AService implements AutoCloseable {",
                            "  @Inject public AService(Thing thing, Thing again) {}",
                            "  public void close() {}",
                            "}")
                }));
    }

    @ParameterizedTest(name = "through a {0}")
    @MethodSource("prototypesBetweenTwoSingletons")
    void singletonIsClosedBeforeTheSingletonItReachesThroughAPrototype(String name, JavaFileObject[] sources)
            throws IOException {
        String container = generated(compile(sources), "TikoContainerImpl");

        assertThat(container).containsSubsequence("aService.close()", "pool.close()");
    }

    @Test
    void failureReportsDoNotLetAThrowingErrorHandlerReplaceTheFailure() throws IOException {
        Compilation compilation = compile(
                source(
                        "Starts",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Starts {",
                        "  @PostConstruct public void init() {}",
                        "}"),
                source(
                        "SelfMade",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class SelfMade {",
                        "  private SelfMade() {}",
                        "  @Produces(scope = Scope.SINGLETON) public static SelfMade open() { return new SelfMade(); }",
                        "}"));

        for (String factory : new String[] {"StartsFactory", "SelfMadeFactory"}) {
            assertThat(generated(compilation, factory))
                    .as(factory)
                    .containsSubsequence(
                            "container.getErrorHandler().onError(",
                            "catch (RuntimeException __handlerFailure)",
                            "__t.addSuppressed(__handlerFailure)",
                            "sneakyThrow(__t)");
        }
    }

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }

    /** Compiles and asserts success: every test here reads what a clean compile generated. */
    private static Compilation compile(JavaFileObject... sources) {
        Compilation compilation = CompileTesting.compile(sources);
        CompilationSubject.assertThat(compilation).succeeded();
        return compilation;
    }
}
