package io.tiko.processor.validation;

import static io.tiko.processor.validation.ShapeTesting.assertLocatedError;
import static io.tiko.processor.validation.ShapeTesting.compile;
import static io.tiko.processor.validation.ShapeTesting.errorsOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An injection point resolves a type exactly when {@code container.get(type)} (or
 * {@code get(type, name)}) would (#724): by every interface the component is routable as, a
 * parameterized one included, and by its own class, under {@code expose} and {@code exposeSelf};
 * a named component answers an unqualified request only by its own class.
 */
class InjectionByRoutableTypeTest {

    private static final String IMPORTS = """
            import io.tiko.Scope;
            import io.tiko.annotations.Component;
            import io.tiko.annotations.Inject;
            import io.tiko.annotations.Named;
            """;

    private static final String A = "public interface A {}";
    private static final String B = "public interface B {}";
    private static final String REPO = "public interface Repo<T> {}";

    private static final String USER_REPO = """
            @Component(scope = Scope.SINGLETON)
            public class UserRepo implements Repo<String> {}
            """;

    private static final String COUNT_REPO = """
            @Component(scope = Scope.SINGLETON)
            public class CountRepo implements Repo<Integer> {}
            """;

    private static String consumer(String parameter) {
        return """
                @Component(scope = Scope.SINGLETON)
                public class Consumer {
                    @Inject public Consumer(%s) {}
                }
                """.formatted(parameter);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolved")
    void injectionPointResolvesWhatTheContainerRoutes(String name, List<String> sources) {
        var compilation = compile(IMPORTS, sources);

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    static Stream<Arguments> resolved() {
        return Stream.of(
                Arguments.of("second interface", List.of(A, B, """
                                @Component(scope = Scope.SINGLETON)
                                public class Impl implements A, B {}
                                """, consumer("B b"))),
                Arguments.of("parameterized interface", List.of(REPO, USER_REPO, consumer("Repo<String> repo"))),
                Arguments.of(
                        "parameterized interface as a @Produces parameter", List.of(REPO, USER_REPO, """
                                @Component(scope = Scope.SINGLETON)
                                public class Wiring {
                                    @io.tiko.annotations.Produces(scope = Scope.SINGLETON)
                                    public StringBuilder names(Repo<String> repo, B second) {
                                        return new StringBuilder();
                                    }
                                }
                                """, A, B, """
                                @Component(scope = Scope.SINGLETON)
                                public class Impl implements A, B {}
                                """)),
                Arguments.of("exposed interface that is not the first one", List.of(A, B, """
                                @Component(scope = Scope.SINGLETON, expose = {A.class})
                                public class Impl implements B, A {}
                                """, consumer("A a"))),
                Arguments.of("exposed parameterized interface", List.of(A, REPO, """
                                @Component(scope = Scope.SINGLETON, expose = {Repo.class})
                                public class UserRepo implements A, Repo<String> {}
                                """, consumer("Repo<String> repo"))),
                Arguments.of("named component by its own class", List.of("""
                                @Component(scope = Scope.SINGLETON, name = "x")
                                public class Impl {}
                                """, consumer("Impl impl"))),
                Arguments.of(
                        "named component by its second interface and name",
                        List.of(A, B, """
                                @Component(scope = Scope.SINGLETON, name = "x")
                                public class Impl implements A, B {}
                                """, consumer("@Named(\"x\") B b"))),
                Arguments.of("the only provider is a named component", List.of(A, """
                                @Component(scope = Scope.SINGLETON, name = "x")
                                public class ImplX implements A {}
                                """, consumer("A a"))),
                Arguments.of("the only provider is a named @Produces method", List.of(A, """
                                @Component(scope = Scope.SINGLETON)
                                public class Wiring {
                                    @io.tiko.annotations.Produces(scope = Scope.SINGLETON, name = "x")
                                    public A a() { return new A() {}; }
                                }
                                """, consumer("A a"))),
                Arguments.of("unnamed component next to named ones", List.of(A, """
                                @Component(scope = Scope.SINGLETON, name = "x")
                                public class ImplX implements A {}
                                """, """
                                @Component(scope = Scope.SINGLETON)
                                public class ImplDefault implements A {}
                                """, consumer("A a"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unresolved")
    void injectionPointTheContainerWouldNotRouteIsALocatedError(
            String name, List<String> sources, List<String> fragments) {
        assertLocatedError(compile(IMPORTS, sources), fragments);
    }

    static Stream<Arguments> unresolved() {
        return Stream.of(
                Arguments.of(
                        "interface hidden by expose",
                        List.of(A, B, """
                                @Component(scope = Scope.SINGLETON, expose = {A.class})
                                public class Impl implements B, A {}
                                """, consumer("B b")),
                        List.of("demo.Impl implements demo.B but does not expose it for injection")),
                Arguments.of(
                        "own class hidden by exposeSelf = false",
                        List.of(A, """
                                @Component(scope = Scope.SINGLETON, exposeSelf = false)
                                public class Impl implements A {}
                                """, consumer("Impl impl")),
                        List.of("demo.Impl", "exposeSelf = false", "Inject demo.A")),
                Arguments.of(
                        "several named implementations and no unnamed one for an unqualified request",
                        List.of(A, """
                                @Component(scope = Scope.SINGLETON, name = "x")
                                public class ImplX implements A {}
                                """, """
                                @Component(scope = Scope.SINGLETON, name = "y")
                                public class ImplY implements A {}
                                """, consumer("A a")),
                        List.of(
                                "Cannot resolve dependency: demo.A",
                                "only named",
                                "\"x\" (demo.ImplX)",
                                "\"y\" (demo.ImplY)",
                                "@Named(\"x\")")),
                Arguments.of(
                        "second interface of a proxied EVENT component",
                        List.of(A, B, """
                                @Component(scope = Scope.EVENT)
                                public class Impl implements A, B {}
                                """, consumer("B b")),
                        List.of("Impl is EVENT-scoped and proxied", "implements demo.A", "Inject demo.A instead")),
                Arguments.of(
                        "parameterized request with one implementation per type argument",
                        List.of(REPO, USER_REPO, COUNT_REPO, consumer("Repo<String> users")),
                        List.of("Multiple unnamed providers for type demo.Repo", "UserRepo", "CountRepo")),
                Arguments.of(
                        "parameterized request that reaches an implementation of other type arguments",
                        List.of(REPO, COUNT_REPO, consumer("Repo<String> users")),
                        List.of(
                                "Cannot inject demo.Repo<java.lang.String>",
                                "demo.CountRepo is the component routed as demo.Repo")),
                Arguments.of(
                        "raw request with one implementation per type argument",
                        List.of(REPO, USER_REPO, COUNT_REPO, consumer("Repo repo")),
                        List.of("demo.Repo", "UserRepo", "CountRepo")));
    }
}
