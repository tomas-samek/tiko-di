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
 * How the generated container resolves at its edges: an override reaching a {@code @Produces}
 * parameter (#738), a proxied EVENT component looked up by its own class (#740), and
 * {@code @Pick} of an EVENT implementation on an interface-typed parameter (#745).
 */
class LookupGenerationTest {

    private static final String IMPORTS =
            "import io.tiko.Scope; import io.tiko.Provider; import io.tiko.annotations.*;";

    private static final JavaFileObject GATEWAY = source("Gateway", "public interface Gateway {}");

    private static final JavaFileObject GATEWAY_IMPL = source(
            "GatewayImpl", "@Component(scope = Scope.SINGLETON)", "public class GatewayImpl implements Gateway {}");

    @Test
    void producesParametersConsultTheOverrideOfTheirDeclaredType() throws IOException {
        String container = container(
                GATEWAY,
                GATEWAY_IMPL,
                source(
                        "NamedGateway",
                        "@Component(scope = Scope.SINGLETON, name = \"backup\")",
                        "public class NamedGateway implements Gateway {}"),
                source(
                        "Things",
                        "@Component",
                        "public class Things {",
                        "  @Produces public String direct(Gateway gateway) { return \"d\"; }",
                        "  @Produces public Integer named(@Named(\"backup\") Gateway gateway) { return 1; }",
                        "  @Produces public Long lazy(Provider<Gateway> gateway) { return 1L; }",
                        "}"));

        assertThat(FactoryInvocation.of(container, "Things_direct"))
                .contains("__arg0 = options.resolveOverride(demo.Gateway.class, () -> getGatewayImpl());")
                .contains("return __declaring.direct(__arg0);");
        assertThat(FactoryInvocation.of(container, "Things_named"))
                .contains("__arg0 = options.resolveOverride(demo.Gateway.class, \"backup\", () -> ");
        assertThat(FactoryInvocation.of(container, "Things_lazy"))
                .contains("__arg0 = () -> options.resolveOverride(demo.Gateway.class, () -> getGatewayImpl());");
    }

    private static final JavaFileObject CTX = source("Ctx", "public interface Ctx {}");

    @Test
    void proxiedEventComponentIsServedByItsProxyUnderTheInterfaceAndByTheInstanceUnderItsOwnClass() throws IOException {
        String container = container(
                CTX, source("CtxImpl", "@Component(scope = Scope.EVENT)", "public class CtxImpl implements Ctx {}"));

        assertThat(container)
                .containsSubsequence("if (type == Ctx.class)", "return (T) getCtxImpl();")
                .containsSubsequence("if (type == CtxImpl.class)", "return (T) getCurrentCtxImpl();")
                .containsSubsequence("__result.add(type.cast(getCtxImpl()));")
                .containsSubsequence("__result.add(type.cast(getCurrentCtxImpl()));");
    }

    @Test
    void namedProxiedEventComponentFollowsTheSameRuleByName() throws IOException {
        String container = container(
                CTX,
                source(
                        "CtxImpl",
                        "@Component(scope = Scope.EVENT, name = \"main\")",
                        "public class CtxImpl implements Ctx {}"));

        assertThat(container)
                .containsSubsequence(
                        "\"main\".equals(name) && type == Ctx.class", "return (T) getCtxImpl();",
                        "\"main\".equals(name) && type == CtxImpl.class", "return (T) getCurrentCtxImpl();");
    }

    private static final JavaFileObject[] TWO_EVENT_CONTEXTS = {
        CTX,
        source("CtxA", "@Component(scope = Scope.EVENT)", "public class CtxA implements Ctx {}"),
        source("CtxB", "@Component(scope = Scope.EVENT)", "public class CtxB implements Ctx {}")
    };

    @Test
    void pickOfAnEventImplementationOnAnInterfaceTypedParameterCompiles() {
        Compilation c = compile(
                TWO_EVENT_CONTEXTS[0],
                TWO_EVENT_CONTEXTS[1],
                TWO_EVENT_CONTEXTS[2],
                source(
                        "Service",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Service {",
                        "  @Inject public Service(@Pick(CtxA.class) Ctx ctx) {}",
                        "}"));

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void pickOfAnEventImplementationOnAParameterTypedByThatClassIsStillRejected() {
        Compilation c = compile(
                TWO_EVENT_CONTEXTS[0],
                TWO_EVENT_CONTEXTS[1],
                TWO_EVENT_CONTEXTS[2],
                source(
                        "Service",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Service {",
                        "  @Inject public Service(@Pick(CtxA.class) CtxA ctx) {}",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("cannot be typed by the concrete class");
    }

    private static JavaFileObject source(String name, String... lines) {
        var all = new String[lines.length + 2];
        all[0] = "package demo;";
        all[1] = IMPORTS;
        System.arraycopy(lines, 0, all, 2, lines.length);
        return JavaFileObjects.forSourceLines("demo." + name, all);
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    private static String container(JavaFileObject... sources) throws IOException {
        Compilation compilation = compile(sources);
        CompilationSubject.assertThat(compilation).succeeded();
        JavaFileObject file = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
