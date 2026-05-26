package io.tiko.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Pins the cross-scope proxy interface requirement (#164, #159).
 *
 * <p>CLAUDE.md documents that injecting a shorter-lived (REQUEST/EVENT) bean into a
 * longer-lived consumer is mediated by a generated proxy, "(requires interface)". The
 * proxy implements an interface and resolves the current scope's instance on every call,
 * so a concrete shorter-lived bean cannot be proxied — it must be a compile error rather
 * than a silent direct-injection that breaks scope semantics at runtime.
 *
 * <p>Covers the three proxy directions (SINGLETON&larr;REQUEST, SINGLETON&larr;EVENT,
 * REQUEST&larr;EVENT) in both the negative (no interface &rarr; error) and positive
 * (interface present &rarr; compiles) forms, plus the {@code @Produces}-output equivalent.
 */
class RequiredInterfaceForProxyTest {

    /** Consumer scope strictly outlives provider scope — the proxy-required directions. */
    static Stream<Arguments> proxyDirections() {
        return Stream.of(
                Arguments.of("SINGLETON", "REQUEST"),
                Arguments.of("SINGLETON", "EVENT"),
                Arguments.of("REQUEST", "EVENT"));
    }

    @ParameterizedTest(name = "{0} consumer injecting concrete {1} bean fails compilation")
    @MethodSource("proxyDirections")
    void concreteShorterLivedBeanInjectedIntoLongerConsumerFailsCompilation(
            String consumerScope, String providerScope) {
        JavaFileObject dep = JavaFileObjects.forSourceLines(
                "io.example.Dep",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope." + providerScope + ")",
                "public class Dep {", // concrete class, no interface
                "  public String value() { return \"x\"; }",
                "}");
        JavaFileObject svc = JavaFileObjects.forSourceLines(
                "io.example.Svc",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope." + consumerScope + ")",
                "public class Svc {",
                "  @Inject public Svc(Dep d) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(dep, svc);

        CompilationSubject.assertThat(c).failed();
        // Names the offending bean, its scope, the consumer, and the consumer's scope.
        CompilationSubject.assertThat(c).hadErrorContaining("Dep must implement an interface");
        CompilationSubject.assertThat(c).hadErrorContaining(providerScope);
        CompilationSubject.assertThat(c).hadErrorContaining("Svc");
        CompilationSubject.assertThat(c).hadErrorContaining(consumerScope);
    }

    @ParameterizedTest(name = "{0} consumer injecting {1} bean via interface compiles")
    @MethodSource("proxyDirections")
    void shorterLivedBeanWithInterfaceInjectedIntoLongerConsumerCompiles(String consumerScope, String providerScope) {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "io.example.Dep", "package io.example;", "public interface Dep { String value(); }");
        JavaFileObject impl = JavaFileObjects.forSourceLines(
                "io.example.DepImpl",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope." + providerScope + ")",
                "public class DepImpl implements Dep {",
                "  public String value() { return \"x\"; }",
                "}");
        JavaFileObject svc = JavaFileObjects.forSourceLines(
                "io.example.Svc",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope." + consumerScope + ")",
                "public class Svc {",
                "  @Inject public Svc(Dep d) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, svc);

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void concreteProducesOutputInjectedIntoLongerConsumerFailsCompilation() {
        JavaFileObject dep = JavaFileObjects.forSourceLines(
                "io.example.Dep",
                "package io.example;",
                "public class Dep { public String value() { return \"x\"; } }"); // concrete return type
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.DepFactory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class DepFactory {",
                "  @Produces(scope = Scope.REQUEST)",
                "  public Dep dep() { return new Dep(); }",
                "}");
        JavaFileObject svc = JavaFileObjects.forSourceLines(
                "io.example.Svc",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Svc {",
                "  @Inject public Svc(Dep d) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(dep, factory, svc);

        CompilationSubject.assertThat(c).failed();
        assertThat(c.errors().stream().anyMatch(d -> d.getMessage(null).contains("must implement an interface")))
                .as("@Produces output proxied cross-scope must require an interface return type")
                .isTrue();
    }
}
