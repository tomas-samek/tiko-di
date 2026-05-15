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
 * Cross-scope injection of a {@code @Produces}-output interface into a
 * longer-lived consumer must be mediated by a generated proxy.
 *
 * <p>Before this fix, only {@code @Component}-class outputs got auto-proxied;
 * a SINGLETON consumer that constructor-injected a REQUEST-scoped
 * {@code @Produces} output captured the connection (or whatever) at first
 * construction and reused it across all subsequent scope frames — exactly
 * the leak the proxy mechanism exists to prevent.
 *
 * <p>The fix extends {@code ProxyGenerator} to also produce a proxy class
 * for factory-method outputs whose declared scope is REQUEST or EVENT and
 * whose return type is an interface. The companion change in
 * {@code ComponentFactoryGenerator} instantiates the proxy when the
 * consumer's scope outlives the factory's scope.
 */
class ProxyForProducesOutputCrossScopeTest {

    @Test
    void proxyClassGeneratedForFactoryOutputAndUsedByLongerLivedConsumer() throws IOException {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "io.example.Greeting", "package io.example;", "public interface Greeting {", "  String hello();", "}");

        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.GreetingFactory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class GreetingFactory {",
                "  @Produces(scope = Scope.REQUEST)",
                "  public Greeting greeting() { return () -> \"hi\"; }",
                "}");

        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "io.example.GreetingConsumer",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class GreetingConsumer {",
                "  @Inject",
                "  public GreetingConsumer(Greeting g) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, factory, consumer);

        // Without the fix, javac compiles the generated GreetingConsumerFactory.java
        // and it works (per PR #93), but the singleton captures a closed/leaked
        // connection on the second REQUEST scope. There is no compile-time symptom —
        // the symptom only surfaces at runtime. So this test asserts the
        // *structural* fix: a proxy class must exist, and the consumer factory
        // must instantiate it instead of calling produce_*() directly.
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject proxySource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("GreetingFactory_greetingProxy"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected GreetingFactory_greetingProxy.java in generated sources but found: "
                                + c.generatedSourceFiles().stream()
                                        .map(JavaFileObject::getName)
                                        .toList()));

        String proxyBody = new String(proxySource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The proxy implements the factory's return-type interface and
        // delegates each interface method to container.produce_<id>().
        assertThat(proxyBody).contains("implements Greeting");
        assertThat(proxyBody).contains("container.produce_GreetingFactory_greeting()");
        assertThat(proxyBody).contains("public String hello()");

        JavaFileObject consumerFactorySource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("GreetingConsumerFactory"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GreetingConsumerFactory was not generated"));

        String consumerFactoryBody =
                new String(consumerFactorySource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The consumer's factory must instantiate the proxy, not call produce_*()
        // directly — that's what makes the SINGLETON pick up the current REQUEST
        // scope's connection on every method call.
        assertThat(consumerFactoryBody).contains("new GreetingFactory_greetingProxy(container)");
        assertThat(consumerFactoryBody).doesNotContain("container.produce_GreetingFactory_greeting()");
    }
}
