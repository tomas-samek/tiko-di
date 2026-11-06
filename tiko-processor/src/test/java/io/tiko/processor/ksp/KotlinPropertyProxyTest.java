package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import org.junit.jupiter.api.Test;

/**
 * A SINGLETON &larr; EVENT proxy over a Kotlin interface with abstract properties: the proxy
 * overrides their accessors ({@code getX} / {@code isX} / {@code setX}, as javac sees a
 * Kotlin-compiled interface), and calls through it reach the current unit's instance.
 */
class KotlinPropertyProxyTest {

    @Test
    void proxyOverridesAbstractPropertyAccessors() throws Exception {
        var source = KspCompiler.kotlin(
                "Request.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "interface RequestContext {",
                "    val requestId: String",
                "    var user: String",
                "    val isAdmin: Boolean",
                "    fun touch(): Int",
                "}",
                "",
                "@Component(scope = Scope.EVENT)",
                "class RequestContextImpl : RequestContext {",
                "    override val requestId: String = \"r-1\"",
                "    override var user: String = \"anonymous\"",
                "    override val isAdmin: Boolean = true",
                "    private var touches = 0",
                "    override fun touch(): Int = ++touches",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Handler(val context: RequestContext) {",
                "    fun describe(): String {",
                "        context.user = \"alice\"",
                "        return context.requestId + \"/\" + context.user + \"/\" + context.isAdmin + \"/\" + context.touch()",
                "    }",
                "}",
                "",
                "object Recorder {",
                "    @JvmField val calls = java.util.concurrent.CopyOnWriteArrayList<String>()",
                "}");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(result.generatedJava("io.tiko.generated.RequestContextImplProxy"))
                .hasValueSatisfying(proxy -> assertThat(proxy)
                        .contains(
                                "public String getRequestId()",
                                "public String getUser()",
                                "public void setUser(String ",
                                "public boolean isAdmin()",
                                "public int touch()"));
        assertThat(result.resources().entrySet())
                .as("explain_proxy data in topology.json")
                .anySatisfy(resource -> {
                    assertThat(resource.getKey()).endsWith("topology.json");
                    assertThat(resource.getValue()).contains("\"getRequestId\"", "\"setUser\"");
                });
        try (var program = KspProgram.boot(result)) {
            var handler = program.get("app.Handler");
            program.runInEventScope(
                    () -> program.staticList("app.Recorder", "calls").add(invoke(handler, "describe")));
            assertThat(program.staticList("app.Recorder", "calls")).containsExactly("r-1/alice/true/1");
        }
    }

    private static Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(method + " failed", e);
        }
    }
}
