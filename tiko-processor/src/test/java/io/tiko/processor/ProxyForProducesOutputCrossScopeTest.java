package io.tiko.processor;

import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Cross-scope injection of a {@code @Produces}-output interface into a
 * longer-lived consumer must be mediated by a generated proxy.
 *
 * <p>Before this fix, only {@code @Component}-class outputs got auto-proxied;
 * a SINGLETON consumer that constructor-injected an EVENT-scoped
 * {@code @Produces} output captured the connection (or whatever) at first
 * construction and reused it across all subsequent scope frames — exactly
 * the leak the proxy mechanism exists to prevent.
 *
 * <p>The fix extends {@code ProxyGenerator} to also produce a proxy class
 * for factory-method outputs whose declared scope is EVENT and whose return
 * type is an interface. The companion change in {@code ComponentFactoryGenerator}
 * instantiates the proxy when the consumer's scope outlives the factory's scope.
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
                "  @Produces(scope = Scope.EVENT)",
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

        Compilation c = compile(iface, factory, consumer);

        // Without the fix, javac compiles the generated GreetingConsumerFactory.java
        // and it works (per PR #93), but the singleton captures a closed/leaked
        // connection in the second unit of work (EVENT scope). There is no compile-time symptom —
        // the symptom only surfaces at runtime. So this test asserts the
        // *structural* fix: a proxy class must exist, and the consumer factory
        // must instantiate it instead of calling produce_*() directly.
        CompilationSubject.assertThat(c).succeeded();

        String proxyBody = generated(c, "GreetingFactory_greetingProxy");

        // The proxy implements the factory's return-type interface and
        // delegates each interface method to container.produce_<id>().
        assertThat(proxyBody).contains("implements Greeting");
        assertThat(proxyBody).contains("container.produce_GreetingFactory_greeting()");
        assertThat(proxyBody).contains("public String hello()");

        String consumerFactoryBody = generated(c, "GreetingConsumerFactory");

        // The consumer's factory must instantiate the proxy, not call produce_*()
        // directly — that's what makes the SINGLETON pick up the current REQUEST
        // scope's connection on every method call.
        assertThat(consumerFactoryBody).containsPattern("new GreetingFactory_greetingProxy_\\w+\\(container\\)");
        assertThat(consumerFactoryBody).doesNotContain("container.produce_GreetingFactory_greeting()");
    }

    private static final JavaFileObject GREETING = JavaFileObjects.forSourceLines(
            "io.example.Greeting", "package io.example;", "public interface Greeting {", "  String hello();", "}");

    private static String containerSourceFor(String... greetingFactoryMethods) throws IOException {
        var lines = new java.util.ArrayList<String>(java.util.List.of(
                "package io.example;",
                "import io.tiko.Provider;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class GreetingFactory {",
                "  @Produces(scope = Scope.EVENT)",
                "  public Greeting greeting() { return () -> \"hi\"; }"));
        lines.addAll(java.util.List.of(greetingFactoryMethods));
        lines.add("}");
        Compilation c = compile(GREETING, JavaFileObjects.forSourceLines("io.example.GreetingFactory", lines));
        CompilationSubject.assertThat(c).succeeded();
        return generated(c, "TikoContainerImpl");
    }

    /**
     * #725: a SINGLETON {@code @Produces} method is a longer-lived consumer too. Its EVENT-scoped
     * {@code @Produces} parameter must be the proxy, as a SINGLETON constructor parameter is; the value
     * itself would be the first unit's instance in every later unit.
     */
    @Test
    void singletonProducesMethodReceivesTheProxyOfAnEventScopedProducesParameter() throws IOException {
        String container = containerSourceFor(
                "  @Produces(scope = Scope.SINGLETON)", "  public String shout(Greeting g) { return g.hello(); }");

        assertThat(FactoryInvocation.of(container, "GreetingFactory_shout"))
                .containsPattern("__arg0 = options\\.resolveOverride\\(io\\.example\\.Greeting\\.class,"
                        + " \\(\\) -> new GreetingFactory_greetingProxy_\\w+\\(this\\)\\);")
                .contains("return __declaring.shout(__arg0);");
        assertThat(container).doesNotContain("() -> produce_GreetingFactory_greeting()");
    }

    @Test
    void sameOrShorterLivedProducesMethodReceivesTheEventScopedValueItself() throws IOException {
        String container = containerSourceFor(
                "  @Produces(scope = Scope.EVENT)",
                "  public String perUnit(Greeting g) { return g.hello(); }",
                "  @Produces",
                "  public Integer perUse(Greeting g) { return 1; }");

        String value = "__arg0 = options.resolveOverride(io.example.Greeting.class,"
                + " () -> produce_GreetingFactory_greeting());";
        assertThat(FactoryInvocation.of(container, "GreetingFactory_perUnit")).contains(value);
        assertThat(FactoryInvocation.of(container, "GreetingFactory_perUse")).contains(value);
        assertThat(container).doesNotContain("GreetingFactory_greetingProxy");
    }

    @Test
    void singletonProducesMethodTakingAProviderResolvesTheValueOnEachCall() throws IOException {
        String container = containerSourceFor(
                "  @Produces(scope = Scope.SINGLETON)",
                "  public String lazy(Provider<Greeting> g) { return \"lazy\"; }");

        assertThat(FactoryInvocation.of(container, "GreetingFactory_lazy"))
                .contains("__arg0 = () -> options.resolveOverride(io.example.Greeting.class,"
                        + " () -> produce_GreetingFactory_greeting());");
        assertThat(container).doesNotContain("GreetingFactory_greetingProxy");
    }
}
