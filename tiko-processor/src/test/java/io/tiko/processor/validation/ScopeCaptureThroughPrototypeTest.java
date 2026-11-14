package io.tiko.processor.validation;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
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

    private static final JavaFileObject CONCRETE_EVENT_CTX =
            source("demo.Ctx", "@Component(scope = Scope.EVENT)", "public class Ctx {", "}");

    private static final JavaFileObject PROTOTYPE_MID_TAKING_CTX =
            source("demo.Mid", "@Component", "public class Mid {", "  @Inject public Mid(Ctx ctx) {}", "}");

    static Stream<Arguments> singletonConsumersOfThePrototype() {
        return Stream.of(
                Arguments.of(
                        "SINGLETON @Component",
                        source(
                                "demo.Holder",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class Holder {",
                                "  @Inject public Holder(Mid mid) {}",
                                "}")),
                Arguments.of(
                        "SINGLETON @Produces",
                        source(
                                "demo.Holders",
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
                source("demo.Outer", "@Component", "public class Outer {", "  @Inject public Outer(Mid mid) {}", "}"),
                source(
                        "demo.Holder",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Holder {",
                        "  @Inject public Holder(Outer outer) {}",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Outer' -> PROTOTYPE 'Mid'");
    }

    @Test
    void singletonReachingAnEventProducedValueThroughAPrototypeFailsCompilation() {
        Compilation c = compile(
                source("demo.Conn", "public interface Conn {", "}"),
                source(
                        "demo.Conns",
                        "@Component",
                        "public class Conns {",
                        "  @Produces(scope = Scope.EVENT) public Conn conn() { return new Conn() {}; }",
                        "}"),
                source("demo.Mid", "@Component", "public class Mid {", "  @Inject public Mid(Conn conn) {}", "}"),
                source(
                        "demo.Holder",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Holder {",
                        "  @Inject public Holder(Mid mid) {}",
                        "}"));

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
                source("demo.Thing", "public class Thing {", "}"),
                source(
                        "demo.Things",
                        "@Component",
                        "public class Things {",
                        "  @Produces public Thing thing(Ctx ctx) { return new Thing(); }",
                        "}"),
                source(
                        "demo.Holder",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Holder {",
                        "  @Inject public Holder(Thing thing) {}",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("through PROTOTYPE 'Things.thing'");
    }

    static Stream<Arguments> graphsThatStayValid() {
        JavaFileObject ctxApi = source("demo.CtxApi", "public interface CtxApi {", "}");
        JavaFileObject proxiedCtx =
                source("demo.Ctx", "@Component(scope = Scope.EVENT)", "public class Ctx implements CtxApi {", "}");
        JavaFileObject midTakingTheInterface =
                source("demo.Mid", "@Component", "public class Mid {", "  @Inject public Mid(CtxApi ctx) {}", "}");
        JavaFileObject singletonHolder = source(
                "demo.Holder",
                "@Component(scope = Scope.SINGLETON)",
                "public class Holder {",
                "  @Inject public Holder(Mid mid) {}",
                "}");
        return Stream.of(
                Arguments.of(
                        "PROTOTYPE receives the EVENT bean through its interface",
                        new JavaFileObject[] {ctxApi, proxiedCtx, midTakingTheInterface, singletonHolder}),
                Arguments.of("SINGLETON takes the PROTOTYPE as a Provider", new JavaFileObject[] {
                    CONCRETE_EVENT_CTX,
                    PROTOTYPE_MID_TAKING_CTX,
                    source(
                            "demo.Holder",
                            "@Component(scope = Scope.SINGLETON)",
                            "public class Holder {",
                            "  @Inject public Holder(Provider<Mid> mid) {}",
                            "}")
                }),
                Arguments.of("PROTOTYPE takes the concrete EVENT bean as a Provider", new JavaFileObject[] {
                    CONCRETE_EVENT_CTX,
                    source(
                            "demo.Mid",
                            "@Component",
                            "public class Mid {",
                            "  @Inject public Mid(Provider<Ctx> ctx) {}",
                            "}"),
                    singletonHolder
                }),
                Arguments.of("EVENT consumer of the PROTOTYPE", new JavaFileObject[] {
                    CONCRETE_EVENT_CTX,
                    PROTOTYPE_MID_TAKING_CTX,
                    source(
                            "demo.Holder",
                            "@Component(scope = Scope.EVENT)",
                            "public class Holder {",
                            "  @Inject public Holder(Mid mid) {}",
                            "}")
                }),
                Arguments.of(
                        "PROTOTYPE consumer of the PROTOTYPE, nothing longer-lived above it", new JavaFileObject[] {
                            CONCRETE_EVENT_CTX,
                            PROTOTYPE_MID_TAKING_CTX,
                            source(
                                    "demo.Outer",
                                    "@Component",
                                    "public class Outer {",
                                    "  @Inject public Outer(Mid mid) {}",
                                    "}")
                        }),
                Arguments.of("PROTOTYPE cycle broken by a Provider below a SINGLETON", new JavaFileObject[] {
                    source("demo.A", "@Component", "public class A {", "  @Inject public A(Provider<B> b) {}", "}"),
                    source("demo.B", "@Component", "public class B {", "  @Inject public B(A a) {}", "}"),
                    source(
                            "demo.Holder",
                            "@Component(scope = Scope.SINGLETON)",
                            "public class Holder {",
                            "  @Inject public Holder(B b) {}",
                            "}")
                }),
                Arguments.of("diamond of PROTOTYPEs over a SINGLETON and the event bus", new JavaFileObject[] {
                    source("demo.Shared", "@Component(scope = Scope.SINGLETON)", "public class Shared {", "}"),
                    source(
                            "demo.Leaf",
                            "@Component",
                            "public class Leaf {",
                            "  @Inject public Leaf(Shared shared, io.tiko.EventBus bus) {}",
                            "}"),
                    source(
                            "demo.Left",
                            "@Component",
                            "public class Left {",
                            "  @Inject public Left(Leaf leaf) {}",
                            "}"),
                    source(
                            "demo.Top",
                            "@Component",
                            "public class Top {",
                            "  @Inject public Top(Left left, Leaf leaf) {}",
                            "}"),
                    source(
                            "demo.Holder",
                            "@Component(scope = Scope.SINGLETON)",
                            "public class Holder {",
                            "  @Inject public Holder(Top top) {}",
                            "}")
                }),
                Arguments.of("static SINGLETON @Produces on an EVENT component", new JavaFileObject[] {
                    source(
                            "demo.Cfg",
                            "@Component(scope = Scope.EVENT)",
                            "public class Cfg {",
                            "  @Produces(scope = Scope.SINGLETON) public static String thing() { return \"t\"; }",
                            "}")
                }),
                Arguments.of("EVENT @Produces on an EVENT component", new JavaFileObject[] {
                    source(
                            "demo.Cfg",
                            "@Component(scope = Scope.EVENT)",
                            "public class Cfg {",
                            "  @Produces(scope = Scope.EVENT) public String thing() { return \"t\"; }",
                            "}")
                }),
                Arguments.of("PROTOTYPE @Produces on an EVENT component", new JavaFileObject[] {
                    source(
                            "demo.Cfg",
                            "@Component(scope = Scope.EVENT)",
                            "public class Cfg {",
                            "  @Produces public String thing() { return \"t\"; }",
                            "}")
                }),
                Arguments.of("SINGLETON @Produces on a PROTOTYPE component", new JavaFileObject[] {
                    source(
                            "demo.Cfg",
                            "@Component",
                            "public class Cfg {",
                            "  @Produces(scope = Scope.SINGLETON) public String thing() { return \"t\"; }",
                            "}")
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("graphsThatStayValid")
    void graphWithoutACaptureCompiles(String name, JavaFileObject[] sources) {
        CompilationSubject.assertThat(compile(sources)).succeeded();
    }

    @Test
    void singletonInstanceProducesOnAnEventComponentFailsCompilation() {
        Compilation c = compile(source(
                "demo.Cfg",
                "@Component(scope = Scope.EVENT)",
                "public class Cfg {",
                "  @Produces(scope = Scope.SINGLETON) public String thing() { return \"t\"; }",
                "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining(
                        "SINGLETON @Produces method 'Cfg.thing' is declared on EVENT-scoped component 'Cfg'");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        CompilationSubject.assertThat(c).hadErrorContaining("Make 'thing' static");
    }

    private static JavaFileObject source(String fqn, String... lines) {
        var all = new String[lines.length + 2];
        all[0] = "package demo;";
        all[1] = IMPORTS;
        System.arraycopy(lines, 0, all, 2, lines.length);
        return JavaFileObjects.forSourceLines(fqn, all);
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }
}
