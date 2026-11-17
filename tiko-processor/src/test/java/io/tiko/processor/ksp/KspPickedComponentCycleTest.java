package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import org.junit.jupiter.api.Test;

/**
 * #823 on the KSP frontend: a cycle that runs through a {@code @Pick} of a component declared with
 * {@code exposeSelf = false} is a compile error.
 */
class KspPickedComponentCycleTest {

    @Test
    void cycleThroughAPickOfASelfHiddenComponentFailsWithFullPath() {
        var source = KspCompiler.kotlin(
                "PickCycle.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Pick",
                "",
                "interface Port",
                "",
                "@Component(scope = Scope.SINGLETON, exposeSelf = false)",
                "class Impl(val consumer: Consumer) : Port",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Consumer(@Pick(Impl::class) val port: Port)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("Circular dependency detected: app.Consumer -> app.Impl -> app.Consumer")
                .doesNotContain("INTERNAL_ERROR");
    }
}
