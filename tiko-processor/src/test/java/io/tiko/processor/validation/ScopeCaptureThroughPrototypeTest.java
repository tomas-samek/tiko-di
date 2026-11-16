package io.tiko.processor.validation;

import static io.tiko.processor.CompileTesting.compile;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import io.tiko.Scope;
import io.tiko.processor.CompileTesting;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #723: a SINGLETON keeps a PROTOTYPE it injects for its whole life, so whatever that PROTOTYPE injects
 * directly lives as long as the SINGLETON. A concrete EVENT bean reached that way would be one unit's
 * instance held across every later unit, so it is a compile error, as the direct injection already is. An
 * instance {@code @Produces} method that outlives its EVENT declaring component is the same capture.
 */
class ScopeCaptureThroughPrototypeTest {

    private static final String IMPORTS =
            "import io.tiko.Scope; import io.tiko.Provider; import io.tiko.annotations.*;";

    private static final String EVENT_COMPONENT = "@Component(scope = Scope.EVENT)";

    /** The annotation and modifiers of an instance {@code @Produces} method of SINGLETON scope. */
    private static final String SINGLETON_INSTANCE_PRODUCES = "@Produces(scope = Scope.SINGLETON) public";

    private static final JavaFileObject CONCRETE_EVENT_CTX = source("Ctx", EVENT_COMPONENT, "public class Ctx {", "}");

    private static final JavaFileObject PROTOTYPE_MID_TAKING_CTX =
            source("Mid", "@Component", "public class Mid {", "  @Inject public Mid(Ctx ctx) {}", "}");

    private static final JavaFileObject SINGLETON_HOLDER_OF_MID = holder(Scope.SINGLETON, "Mid mid");

    static Stream<Arguments> singletonConsumersOfThePrototype() {
        return Stream.of(
                Arguments.of("SINGLETON @Component", SINGLETON_HOLDER_OF_MID),
                Arguments.of(
                        "SINGLETON @Produces",
                        source(
                                "Holders",
                                "@Component",
                                "public class Holders {",
                                "  @Produces(scope = Scope.SINGLETON) public String holder(Mid mid) { return \"h\"; }",
                                "}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singletonConsumersOfThePrototype")
    void singletonInjectingAPrototypeThatInjectsAConcreteEventBeanFailsCompilation(
            String name, JavaFileObject consumer) {
        Compilation c = compile(CONCRETE_EVENT_CTX, PROTOTYPE_MID_TAKING_CTX, consumer);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Ctx must implement an interface");
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Mid'");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
    }

    @Test
    void captureThroughTwoPrototypesNamesTheWholeChain() {
        Compilation c = compile(
                CONCRETE_EVENT_CTX,
                PROTOTYPE_MID_TAKING_CTX,
                source("Outer", "@Component", "public class Outer {", "  @Inject public Outer(Mid mid) {}", "}"),
                holder(Scope.SINGLETON, "Outer outer"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Outer' -> PROTOTYPE 'Mid'");
    }

    @Test
    void singletonReachingAnEventProducedValueThroughAPrototypeFailsCompilation() {
        Compilation c = compile(
                source("Conn", "public interface Conn {", "}"),
                source(
                        "Conns",
                        "@Component",
                        "public class Conns {",
                        "  @Produces(scope = Scope.EVENT) public Conn conn() { return new Conn() {}; }",
                        "}"),
                source("Mid", "@Component", "public class Mid {", "  @Inject public Mid(Conn conn) {}", "}"),
                SINGLETON_HOLDER_OF_MID);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining("SINGLETON-scoped 'Holder' would keep one unit's 'demo.Conn'");
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Mid'");
        CompilationSubject.assertThat(c).hadErrorContaining("Inject Provider<demo.Conn> into PROTOTYPE 'Mid'");
    }

    @Test
    void captureThroughAPrototypeProducesMethodNamesTheMethod() {
        Compilation c = compile(
                CONCRETE_EVENT_CTX,
                source("Thing", "public class Thing {", "}"),
                source(
                        "Things",
                        "@Component",
                        "public class Things {",
                        "  @Produces public Thing thing(Ctx ctx) { return new Thing(); }",
                        "}"),
                holder(Scope.SINGLETON, "Thing thing"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Things.thing'");
    }

    static Stream<Arguments> graphsThatStayValid() {
        JavaFileObject ctxApi = source("CtxApi", "public interface CtxApi {", "}");
        JavaFileObject proxiedCtx = source("Ctx", EVENT_COMPONENT, "public class Ctx implements CtxApi {", "}");
        JavaFileObject midTakingTheInterface =
                source("Mid", "@Component", "public class Mid {", "  @Inject public Mid(CtxApi ctx) {}", "}");
        return Stream.of(
                Arguments.of(
                        "PROTOTYPE receives the EVENT bean through its interface",
                        new JavaFileObject[] {ctxApi, proxiedCtx, midTakingTheInterface, SINGLETON_HOLDER_OF_MID}),
                Arguments.of("SINGLETON takes the PROTOTYPE as a Provider", new JavaFileObject[] {
                    CONCRETE_EVENT_CTX, PROTOTYPE_MID_TAKING_CTX, holder(Scope.SINGLETON, "Provider<Mid> mid")
                }),
                Arguments.of("PROTOTYPE takes the concrete EVENT bean as a Provider", new JavaFileObject[] {
                    CONCRETE_EVENT_CTX,
                    source(
                            "Mid",
                            "@Component",
                            "public class Mid {",
                            "  @Inject public Mid(Provider<Ctx> ctx) {}",
                            "}"),
                    SINGLETON_HOLDER_OF_MID
                }),
                Arguments.of("EVENT consumer of the PROTOTYPE", new JavaFileObject[] {
                    CONCRETE_EVENT_CTX, PROTOTYPE_MID_TAKING_CTX, holder(Scope.EVENT, "Mid mid")
                }),
                Arguments.of(
                        "PROTOTYPE consumer of the PROTOTYPE, nothing longer-lived above it", new JavaFileObject[] {
                            CONCRETE_EVENT_CTX,
                            PROTOTYPE_MID_TAKING_CTX,
                            source(
                                    "Outer",
                                    "@Component",
                                    "public class Outer {",
                                    "  @Inject public Outer(Mid mid) {}",
                                    "}")
                        }),
                Arguments.of("PROTOTYPE cycle broken by a Provider below a SINGLETON", new JavaFileObject[] {
                    source("A", "@Component", "public class A {", "  @Inject public A(Provider<B> b) {}", "}"),
                    source("B", "@Component", "public class B {", "  @Inject public B(A a) {}", "}"),
                    holder(Scope.SINGLETON, "B b")
                }),
                Arguments.of("diamond of PROTOTYPEs over a SINGLETON and the event bus", new JavaFileObject[] {
                    source("Shared", "@Component(scope = Scope.SINGLETON)", "public class Shared {", "}"),
                    source(
                            "Leaf",
                            "@Component",
                            "public class Leaf {",
                            "  @Inject public Leaf(Shared shared, io.tiko.EventBus bus) {}",
                            "}"),
                    source("Left", "@Component", "public class Left {", "  @Inject public Left(Leaf leaf) {}", "}"),
                    source(
                            "Top",
                            "@Component",
                            "public class Top {",
                            "  @Inject public Top(Left left, Leaf leaf) {}",
                            "}"),
                    holder(Scope.SINGLETON, "Top top")
                }),
                Arguments.of("static SINGLETON @Produces on an EVENT component", new JavaFileObject[] {
                    cfg(EVENT_COMPONENT, "@Produces(scope = Scope.SINGLETON) public static")
                }),
                Arguments.of(
                        "EVENT @Produces on an EVENT component",
                        new JavaFileObject[] {cfg(EVENT_COMPONENT, "@Produces(scope = Scope.EVENT) public")}),
                Arguments.of(
                        "PROTOTYPE @Produces on an EVENT component",
                        new JavaFileObject[] {cfg(EVENT_COMPONENT, "@Produces public")}),
                Arguments.of(
                        "SINGLETON @Produces on a PROTOTYPE component",
                        new JavaFileObject[] {cfg("@Component", SINGLETON_INSTANCE_PRODUCES)}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("graphsThatStayValid")
    void graphWithoutACaptureCompiles(String name, JavaFileObject[] sources) {
        CompilationSubject.assertThat(compile(sources)).succeeded();
    }

    @Test
    void singletonInstanceProducesOnAnEventComponentFailsCompilation() {
        Compilation c = compile(cfg(EVENT_COMPONENT, SINGLETON_INSTANCE_PRODUCES));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining(
                        "SINGLETON @Produces method 'Cfg.thing' is declared on EVENT-scoped component 'Cfg'");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        CompilationSubject.assertThat(c).hadErrorContaining("Make 'thing' static");
    }

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }

    /** A {@code Holder} component of {@code scope} whose constructor injects {@code param}. */
    private static JavaFileObject holder(Scope scope, String param) {
        return source(
                "Holder",
                "@Component(scope = Scope." + scope + ")",
                "public class Holder {",
                "  @Inject public Holder(" + param + ") {}",
                "}");
    }

    /**
     * A {@code Cfg} component annotated {@code componentAnnotation} whose {@code thing()} method is
     * declared with {@code produces}: its {@code @Produces} annotation and its modifiers.
     */
    private static JavaFileObject cfg(String componentAnnotation, String produces) {
        return source(
                "Cfg",
                componentAnnotation,
                "public class Cfg {",
                "  " + produces + " String thing() { return \"t\"; }",
                "}");
    }
}
