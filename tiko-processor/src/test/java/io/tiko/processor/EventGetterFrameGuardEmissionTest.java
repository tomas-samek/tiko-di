package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins the unit-of-work frame guard emission on every EVENT-scoped resolution path (#302).
 *
 * <p>The generated container must refuse to materialize an EVENT-scoped bean while no
 * {@code runInEventScope} / {@code supplyInEventScope} frame is open — otherwise the
 * instance lands in the per-thread scope map with no teardown path. The guard must appear
 * in all three getter shapes: the plain EVENT component getter, the {@code getCurrent*}
 * getter that proxies delegate to, and the {@code produce_*} getter for EVENT-scoped
 * {@code @Produces} outputs.
 */
class EventGetterFrameGuardEmissionTest {

    @Test
    void everyEventScopedGetterEmitsFrameGuard() throws IOException {
        JavaFileObject eventBean = JavaFileObjects.forSourceLines(
                "demo.MyEventBeanImpl",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.EVENT)",
                "public class MyEventBeanImpl {",
                "}");
        // Proxied EVENT component (interface + SINGLETON consumer): after #308, getCurrent*
        // is only emitted for proxied components, so this trio keeps that getter shape covered.
        JavaFileObject reqCtx = JavaFileObjects.forSourceLines(
                "demo.ReqCtx", "package demo;", "public interface ReqCtx {", "  String id();", "}");
        JavaFileObject reqCtxImpl = JavaFileObjects.forSourceLines(
                "demo.ReqCtxImpl",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.EVENT)",
                "public class ReqCtxImpl implements ReqCtx {",
                "  public String id() { return \"x\"; }",
                "}");
        JavaFileObject holder = JavaFileObjects.forSourceLines(
                "demo.Holder",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Holder {",
                "  @Inject public Holder(ReqCtx ctx) {}",
                "}");
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "demo.SessionTokenFactory",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class SessionTokenFactory {",
                "  @Produces(scope = Scope.EVENT)",
                "  public StringBuilder sessionToken() {",
                "    return new StringBuilder();",
                "  }",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(eventBean, reqCtx, reqCtxImpl, holder, factory);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject containerSource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String source = new String(containerSource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(source)
                .as("EVENT-scoped getters must guard against resolution outside a unit-of-work frame")
                .contains("getMyEventBeanImpl")
                .contains("getCurrentReqCtxImpl")
                .contains("produce_SessionTokenFactory_sessionToken")
                .contains("NoActiveEventScopeException");

        // One guard per EVENT-scoped getter shape: the plain getter (getMyEventBeanImpl), the
        // proxy delegate (getCurrentReqCtxImpl — ReqCtxImpl's plain getter returns the proxy
        // field and needs no guard), and the factory getter (produce_..._sessionToken).
        assertThat(countOccurrences(source, "throw new NoActiveEventScopeException"))
                .as("each EVENT-scoped getter carries its own frame guard")
                .isEqualTo(3);
    }

    private static int countOccurrences(String haystack, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
