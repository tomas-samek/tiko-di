package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import org.junit.jupiter.api.Test;

/**
 * {@code internal} Kotlin members are called from generated Java by their JVM (mangled) name
 * (Review Focus 2): with the source name the generated Java would compile on KSP and then fail
 * in javac. The program is compiled, booted with {@code Tiko.create()}, and both members record
 * that they ran.
 */
class KotlinInternalMemberTest {

    @Test
    void internalPostConstructAndHandlerCalledByJvmName() throws Exception {
        var source = KspCompiler.kotlin(
                "Greeter.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.EventHandler",
                "import io.tiko.annotations.PostConstruct",
                "import io.tiko.events.ApplicationStartedEvent",
                "import java.util.concurrent.CopyOnWriteArrayList",
                "",
                "object Recorder {",
                "    @JvmField val calls = CopyOnWriteArrayList<String>()",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Greeter {",
                "    @PostConstruct internal fun ready() { Recorder.calls.add(\"postConstruct\") }",
                "",
                "    @EventHandler internal fun onStarted(event: ApplicationStartedEvent) {",
                "        Recorder.calls.add(\"handler\")",
                "    }",
                "}");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(String.join("\n", result.javaSources().values()))
                .as("mangled JVM names of the internal members")
                .contains("instance.ready$", "__handler.onStarted$");
        try (var program = KspProgram.boot(result)) {
            assertThat(program.staticList("app.Recorder", "calls")).containsExactly("postConstruct", "handler");
        }
    }
}
