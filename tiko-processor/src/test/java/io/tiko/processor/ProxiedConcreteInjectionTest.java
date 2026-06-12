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
 * A proxied EVENT-scoped bean (EVENT + implements an interface) can only be received
 * through its interface — the generated proxy implements the interface, not the concrete
 * class. Injecting it by concrete class used to pass validation and then fail javac with
 * an opaque inference error inside generated sources; an {@code EventHandler} method on
 * such a component generated a proxy-to-concrete assignment mismatch (#331). Now:
 * concrete-typed injection fails with a located Tiko diagnostic, and handler dispatch
 * resolves the current unit's instance via the concrete-typed {@code getCurrentXxx}.
 */
class ProxiedConcreteInjectionTest {

    private static JavaFileObject iface() {
        return JavaFileObjects.forSourceLines("demo.ICtx", "package demo;", "public interface ICtx {}");
    }

    private static JavaFileObject proxiedEventBean(String... extraBody) {
        var lines = new java.util.ArrayList<>(java.util.List.of(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.EVENT)",
                "public class CtxImpl implements ICtx {"));
        lines.addAll(java.util.List.of(extraBody));
        lines.add("}");
        return JavaFileObjects.forSourceLines("demo.CtxImpl", lines.toArray(String[]::new));
    }

    private static JavaFileObject consumer(String scope, String ctorParam) {
        return JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope." + scope + ")",
                "public class Consumer {",
                "  @Inject public Consumer(" + ctorParam + ") {}",
                "}");
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> concreteInjectionShapes() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "SINGLETON consumer, direct", "SINGLETON", "CtxImpl ctx"),
                org.junit.jupiter.params.provider.Arguments.of("EVENT consumer, direct", "EVENT", "CtxImpl ctx"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SINGLETON consumer, Provider-wrapped", "SINGLETON", "io.tiko.Provider<CtxImpl> ctx"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("concreteInjectionShapes")
    void concreteTypedInjectionOfProxiedBeanFailsWithLocatedDiagnostic(String name, String scope, String ctorParam) {
        Compilation c = compile(iface(), proxiedEventBean(), consumer(scope, ctorParam));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("CtxImpl");
        CompilationSubject.assertThat(c).hadErrorContaining("ICtx");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
    }

    @Test
    void interfaceInjectionOfProxiedBeanStillCompiles() {
        Compilation c = compile(iface(), proxiedEventBean(), consumer("SINGLETON", "ICtx ctx"));

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void eventHandlerOnProxiedEventComponentCompilesAndResolvesCurrentInstance() throws IOException {
        JavaFileObject event =
                JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}");
        JavaFileObject bean = JavaFileObjects.forSourceLines(
                "demo.CtxImpl",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.EVENT)",
                "public class CtxImpl implements ICtx {",
                "  @EventHandler",
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c = compile(iface(), event, bean);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EventRegistry"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EventRegistry was not generated"));
        String source = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(source)
                .as("dispatch on a proxied component must resolve the current unit's instance, not the proxy")
                .contains("container.getCurrentCtxImpl()");
    }
}
