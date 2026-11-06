package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A nullable {@code @Produces} that returns {@code null} is cached like any other instance
 * (Review Focus 3): the container stores the null for its SINGLETON / EVENT slot and hands it
 * out again instead of invoking the producer once per injection.
 */
class NullableProducerCachingTest {

    @Test
    void singletonNullProducerInvokedOnce() throws Exception {
        var result = compile("SINGLETON", "SINGLETON");

        try (var program = KspProgram.boot(result)) {
            assertThat(produced(program).get()).isEqualTo(1);
            assertThat(received(program)).containsExactlyInAnyOrder("first:null", "second:null");
        }
    }

    @Test
    void eventNullProducerInvokedOncePerUnit() throws Exception {
        var result = compile("EVENT", "EVENT");

        try (var program = KspProgram.boot(result)) {
            program.runInEventScope(() -> {
                program.get("app.First");
                program.get("app.Second");
                program.get("app.First");
            });
            assertThat(produced(program).get()).as("one call in the first unit").isEqualTo(1);

            program.runInEventScope(() -> program.get("app.Second"));
            assertThat(produced(program).get())
                    .as("one more in the second unit")
                    .isEqualTo(2);
            assertThat(received(program)).containsExactly("first:null", "second:null", "second:null");
        }
    }

    @Test
    void nullCloseableSingletonShutsDownCleanly() throws Exception {
        var source = KspCompiler.kotlin(
                "Graph.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Produces",
                "import java.io.Closeable",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Producers {",
                "    @Produces(scope = Scope.SINGLETON)",
                "    fun resource(): Closeable? = null",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Holder(val resource: Closeable?)");
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);

        var program = KspProgram.boot(result);

        // Shutdown closes the SINGLETON closeables; the cached null must be skipped, not cast.
        assertThatCode(program::close).doesNotThrowAnyException();
    }

    /** A {@code Foo?} producer in {@code producerScope}, injected into two consumers in {@code consumerScope}. */
    private static KspResult compile(String producerScope, String consumerScope) {
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
                "class Foo",
                "",
                "object Calls {",
                "    @JvmField val produced = AtomicInteger()",
                "    @JvmField val received = CopyOnWriteArrayList<String>()",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Producers {",
                "    @Produces(scope = Scope." + producerScope + ")",
                "    fun foo(): Foo? {",
                "        Calls.produced.incrementAndGet()",
                "        return null",
                "    }",
                "}",
                "",
                "@Component(scope = Scope." + consumerScope + ")",
                "class First(foo: Foo?) { init { Calls.received.add(\"first:\" + foo) } }",
                "",
                "@Component(scope = Scope." + consumerScope + ")",
                "class Second(foo: Foo?) { init { Calls.received.add(\"second:\" + foo) } }");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        return result;
    }

    private static AtomicInteger produced(KspProgram program) {
        return (AtomicInteger) program.staticField("app.Calls", "produced");
    }

    private static List<Object> received(KspProgram program) {
        return program.staticList("app.Calls", "received");
    }
}
