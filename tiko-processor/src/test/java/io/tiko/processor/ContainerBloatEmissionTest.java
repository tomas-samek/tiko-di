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
 * Pins the generated-container size cleanups from #308 — all structural, zero behavior change.
 *
 * <ul>
 *   <li>{@code getCurrent*} is emitted only for components a proxy actually delegates to;
 *       for non-proxied EVENT beans it was a dead byte-identical twin of the plain getter.</li>
 *   <li>The EVENT teardown walk is emitted once in a shared {@code __closeEventScope()}
 *       helper called from both {@code runInEventScope} and {@code supplyInEventScope},
 *       instead of being duplicated verbatim in each.</li>
 *   <li>{@code shutdown()} Phase 4 (bypass set + try/finally) is skipped entirely when no
 *       SINGLETON has a destroy hook — no degenerate {@code try { } finally { ... }}.</li>
 * </ul>
 */
class ContainerBloatEmissionTest {

    @Test
    void getCurrentEmittedOnlyForProxiedComponents() throws IOException {
        String source = generateContainerSource(
                JavaFileObjects.forSourceLines(
                        "demo.PlainEventBean",
                        "package demo;",
                        "import io.tiko.Scope;",
                        "import io.tiko.annotations.Component;",
                        "@Component(scope = Scope.EVENT)",
                        "public class PlainEventBean {",
                        "}"),
                JavaFileObjects.forSourceLines(
                        "demo.ReqCtx", "package demo;", "public interface ReqCtx {", "  String id();", "}"),
                JavaFileObjects.forSourceLines(
                        "demo.ReqCtxImpl",
                        "package demo;",
                        "import io.tiko.Scope;",
                        "import io.tiko.annotations.Component;",
                        "@Component(scope = Scope.EVENT)",
                        "public class ReqCtxImpl implements ReqCtx {",
                        "  public String id() { return \"x\"; }",
                        "}"),
                JavaFileObjects.forSourceLines(
                        "demo.Holder",
                        "package demo;",
                        "import io.tiko.Scope;",
                        "import io.tiko.annotations.Component;",
                        "import io.tiko.annotations.Inject;",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Holder {",
                        "  @Inject public Holder(ReqCtx ctx) {}",
                        "}"));

        assertThat(source)
                .as("the proxied EVENT component keeps its getCurrent* delegate target")
                .contains("getCurrentReqCtxImpl")
                .as("a non-proxied EVENT component has no caller for getCurrent* — the dead twin is not emitted")
                .doesNotContain("getCurrentPlainEventBean");
    }

    @Test
    void eventTeardownEmittedOnceAndSharedByBothScopeMethods() throws IOException {
        String source = generateContainerSource(JavaFileObjects.forSourceLines(
                "demo.ClosingEventBean",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PreDestroy;",
                "@Component(scope = Scope.EVENT)",
                "public class ClosingEventBean {",
                "  @PreDestroy public void cleanup() {}",
                "}"));

        assertThat(countOccurrences(source, "__toDestroy"))
                .as("the reverse-LIFO teardown walk is emitted once, not duplicated per scope method")
                .isEqualTo(3); // declaration + size() + get() inside the single shared loop

        assertThat(source).contains("private void __closeEventScope()");
        assertThat(countOccurrences(source, "__closeEventScope();"))
                .as("runInEventScope and supplyInEventScope both delegate to the shared teardown helper")
                .isEqualTo(2);
    }

    @Test
    void noPhase4TryFinallyWhenNoSingletonDestroyHooks() throws IOException {
        String source = generateContainerSource(JavaFileObjects.forSourceLines(
                "demo.PlainService",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PlainService {",
                "}"));

        assertThat(source)
                .as("without SINGLETON destroy hooks, shutdown() skips the bypass set and its try/finally")
                .doesNotContain("inShutdownThread.set(Boolean.TRUE)")
                .doesNotContain("inShutdownThread.remove()");
    }

    private String generateContainerSource(JavaFileObject... sources) throws IOException {
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
