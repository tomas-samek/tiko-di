package io.tiko.processor.scopes;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The canonical map of the 9-cell cross-scope injection matrix (#161, #226): one parameterized row
 * per {@code consumer -> dependency} scope pair, asserting that every cell compiles and generates
 * the expected wiring. Before #161, most cells (EVENT&rarr;EVENT, PROTOTYPE&rarr;*, &hellip;)
 * passed by virtue of not being broken rather than by assertion, so a refactor breaking a specific
 * cell could ship silently. #226 collapsed the 4-scope model (SINGLETON/REQUEST/EVENT/PROTOTYPE)
 * to 3 scopes (SINGLETON/EVENT/PROTOTYPE), removing REQUEST.
 *
 * <p>The dependency is always interface-backed ({@code Dep}/{@code DepImpl}) — interface-keyed
 * injection is valid in every cell (it is what makes the proxy direction legal at all) and matches
 * the house "test against interfaces" rule, so a single uniform fixture covers all 9 cells.
 *
 * <p>Each row asserts: (1) compilation succeeds, (2) the dependency impl's {@code Factory} and the
 * consumer's {@code Factory} are generated, (3) a {@code DepImplProxy} is generated for exactly the
 * three cells whose dependency is EVENT-scoped, and for no others.
 *
 * <p>That proxy rule is worth stating precisely: for a {@code @Component} dependency,
 * {@code requiresProxy} is a property of the <em>dependency</em> alone — "EVENT-scoped behind an
 * interface" — not of the consumer/dependency pair. Such a component is always reached through its
 * generated per-scope resolving proxy, which is its single access path, regardless of whether the
 * consumer outlives it; SINGLETON and PROTOTYPE dependencies are injected directly. (The
 * consumer-outlives gating exists only for {@code @Produces} factory outputs —
 * {@code ComponentFactoryGenerator} — which is a separate matrix from this
 * {@code @Component}-scope one.)
 *
 * <p><strong>Runtime smoke</strong> — retrieving and using a bean inside its scope — is pinned by
 * the existing real-container tests rather than re-asserted here (this layer compiles fixtures and
 * inspects generated source; it does not boot a container):
 * <ul>
 *   <li>cross-scope proxy delegation &amp; per-scope freshness across SINGLETON/EVENT —
 *       {@code CoreDiIntegrationTest} (01_basic_di), which the issue's out-of-scope cites;</li>
 *   <li>PROTOTYPE instance freshness (fresh instance per injection / {@code Provider} call) —
 *       {@code ProviderTest} (01_basic_di);</li>
 *   <li>the negative side of the proxy direction (concrete EVENT-scoped bean &rarr; error) —
 *       {@code RequiredInterfaceForProxyTest}.</li>
 * </ul>
 */
class CrossScopeMatrixTest {

    private static final String[] SCOPES = {"SINGLETON", "EVENT", "PROTOTYPE"};

    /**
     * The dependency scopes that are proxyable behind an interface, so a proxy class is generated
     * and used as the dependency's access path. PROTOTYPE produces a fresh instance per injection
     * and SINGLETON is always live, so neither is proxied.
     */
    private static final Set<String> PROXYABLE_DEP_SCOPES = Set.of("EVENT");

    static Stream<Arguments> matrix() {
        return Stream.of(SCOPES).flatMap(consumer -> Stream.of(SCOPES).map(dep -> Arguments.of(consumer, dep)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("matrix")
    void cellCompilesAndGeneratesExpectedFactoryAndProxy(String consumerScope, String depScope) {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "io.example.Dep", "package io.example;", "public interface Dep { String value(); }");
        JavaFileObject impl = JavaFileObjects.forSourceLines(
                "io.example.DepImpl",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope." + depScope + ")",
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
                "  private final Dep d;",
                "  @Inject public Svc(Dep d) { this.d = d; }",
                "  public String use() { return d.value(); }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, svc);

        String cell = consumerScope + "->" + depScope;

        CompilationSubject.assertThat(c).succeeded();

        assertThat(generated(c, "DepImplFactory"))
                .as("%s: the dependency impl's factory is generated", cell)
                .isTrue();
        assertThat(generated(c, "SvcFactory"))
                .as("%s: the consumer's factory is generated", cell)
                .isTrue();
        assertThat(generated(c, "DepImplProxy"))
                .as("%s: a proxy is generated iff the dependency is EVENT-scoped (proxyable)", cell)
                .isEqualTo(PROXYABLE_DEP_SCOPES.contains(depScope));
    }

    private static boolean generated(Compilation c, String simpleName) {
        return c.generatedSourceFiles().stream().anyMatch(f -> f.getName().contains(simpleName));
    }
}
