package io.tiko.processor;

import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * #741: shapes of an EVENT component's interface that the generated proxy got wrong or could not
 * compile: a parameterized interface, a method inherited from a generic interface, an interface
 * default method the component overrides, and a parameter named like the proxy's own field.
 */
class EventProxyShapesTest {

    private static final String IMPORTS = "import io.tiko.Scope; import io.tiko.annotations.*;";

    @Test
    void eventComponentWhoseOnlyInterfaceIsParameterizedIsNotProxiedAndCompiles() throws IOException {
        Compilation c = compile(source(
                "Amount",
                "@Component(scope = Scope.EVENT)",
                "public class Amount implements Comparable<Amount> {",
                "  public int compareTo(Amount other) { return 0; }",
                "}"));

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "TikoContainerImpl")).doesNotContain("AmountProxy");
    }

    @Test
    void singletonInjectingAnEventComponentThatHasOnlyAParameterizedInterfaceIsRejected() {
        Compilation c = compile(
                source(
                        "Amount",
                        "@Component(scope = Scope.EVENT)",
                        "public class Amount implements Comparable<Amount> {",
                        "  public int compareTo(Amount other) { return 0; }",
                        "}"),
                source(
                        "Ledger",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Ledger {",
                        "  @Inject public Ledger(Amount amount) {}",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Amount must implement an interface");
    }

    private static final JavaFileObject SUPPLIER_CTX_IMPL = source(
            "CtxImpl",
            "@Component(scope = Scope.EVENT)",
            "public class CtxImpl implements Ctx {",
            "  public String get() { return \"x\"; }",
            "}");

    @Test
    void methodInheritedFromAGenericInterfaceIsALocatedErrorWithAFix() {
        Compilation c = compile(
                source("Ctx", "public interface Ctx extends java.util.function.Supplier<String> {}"),
                SUPPLIER_CTX_IMPL);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining("'get' is inherited from a generic interface and its signature uses the type"
                        + " parameter 'T'");
        CompilationSubject.assertThat(c).hadErrorContaining("Redeclare 'get' in demo.Ctx with the concrete types");
    }

    @Test
    void redeclaringTheInheritedGenericMethodWithConcreteTypesCompiles() throws IOException {
        Compilation c = compile(
                source(
                        "Ctx",
                        "public interface Ctx extends java.util.function.Supplier<String> {",
                        "  @Override String get();",
                        "}"),
                SUPPLIER_CTX_IMPL);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "CtxImplProxy")).contains("public String get()");
    }

    @Test
    void proxyDelegatesDefaultMethodsSoAnOverrideInTheComponentIsTheOneCalled() throws IOException {
        Compilation c = compile(
                source(
                        "Ctx",
                        "public interface Ctx {",
                        "  String id();",
                        "  default String describe() { return \"DEFAULT:\" + id(); }",
                        "  static Ctx none() { return null; }",
                        "}"),
                source(
                        "CtxImpl",
                        "@Component(scope = Scope.EVENT)",
                        "public class CtxImpl implements Ctx {",
                        "  public String id() { return \"x\"; }",
                        "  @Override public String describe() { return \"IMPL-OVERRIDE\"; }",
                        "}"));

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "CtxImplProxy"))
                .contains("public String describe()", "getCurrentCtxImpl().describe()")
                .doesNotContain("none()");
    }

    @Test
    void parameterNamedLikeTheProxysContainerFieldDoesNotShadowIt() throws IOException {
        Compilation c = compile(
                source("Ctx", "public interface Ctx {", "  String label(String container);", "}"),
                source(
                        "CtxImpl",
                        "@Component(scope = Scope.EVENT)",
                        "public class CtxImpl implements Ctx {",
                        "  public String label(String container) { return container; }",
                        "}"));

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "CtxImplProxy")).contains("this.container.getCurrentCtxImpl().label(container)");
    }

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }
}
