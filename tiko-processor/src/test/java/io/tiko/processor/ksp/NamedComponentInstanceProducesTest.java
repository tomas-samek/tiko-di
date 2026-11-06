package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** #612 on KSP: the instance {@code @Produces} of a named component runs on that component's instance. */
class NamedComponentInstanceProducesTest {

    @Test
    void producerRunsOnTheNamedInstanceAndFeedsTheConsumer() throws Exception {
        var source = KspCompiler.kotlin(
                "Graph.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Produces",
                "import java.util.concurrent.CopyOnWriteArrayList",
                "import java.util.concurrent.atomic.AtomicInteger",
                "",
                "class Widget",
                "",
                "object Calls {",
                "    @JvmField val produced = AtomicInteger()",
                "    @JvmField val received = CopyOnWriteArrayList<String>()",
                "}",
                "",
                "@Component(name = \"main\", scope = Scope.SINGLETON)",
                "class Producers {",
                "    @Produces(scope = Scope.SINGLETON)",
                "    fun widget(): Widget { Calls.produced.incrementAndGet(); return Widget() }",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Consumer(widget: Widget) { init { Calls.received.add(widget.javaClass.simpleName) } }");
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);

        try (var program = KspProgram.boot(result)) {
            assertThat(((AtomicInteger) program.staticField("app.Calls", "produced")).get())
                    .isEqualTo(1);
            assertThat(program.staticList("app.Calls", "received")).containsExactly("Widget");
        }
    }
}
