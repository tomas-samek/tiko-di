package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import org.junit.jupiter.api.Test;

/**
 * DI errors on KSP point at the Kotlin file and line users read them against (Review Focus 4).
 * A missing constructor dependency is reported on the component class, as on javac; a missing
 * {@code @Produces} dependency on the producer method. Neither declaration sits on line 1, so a
 * position that is lost or mapped wrongly cannot pass by accident.
 */
class KspDiagnosticLocationTest {

    @Test
    void missingDependencyReportedOnKotlinParameterLine() {
        var source = KspCompiler.kotlin(
                "Consumer.kt",
                "package app", // 1
                "", // 2
                "import io.tiko.Scope", // 3
                "import io.tiko.annotations.Component", // 4
                "", // 5
                "interface Gateway", // 6
                "", // 7
                "@Component(scope = Scope.SINGLETON)", // 8
                "class Consumer(", // 9
                "    val gateway: Gateway,", // 10
                ")"); // 11

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertReportedAt(result, "Consumer.kt:9: ", "Cannot resolve dependency: app.Gateway");
    }

    @Test
    void missingProducesDependencyReportedOnKotlinMethodLine() {
        var source = KspCompiler.kotlin(
                "Producers.kt",
                "package app", // 1
                "", // 2
                "import io.tiko.Scope", // 3
                "import io.tiko.annotations.Component", // 4
                "import io.tiko.annotations.Produces", // 5
                "", // 6
                "interface Gateway", // 7
                "class Service(val gateway: Gateway)", // 8
                "", // 9
                "@Component(scope = Scope.SINGLETON)", // 10
                "class Producers {", // 11
                "    @Produces(scope = Scope.SINGLETON)", // 12
                "    fun service(", // 13
                "        gateway: Gateway,", // 14
                "    ): Service = Service(gateway)", // 15
                "}"); // 16

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertReportedAt(result, "Producers.kt:13: ", "Cannot resolve dependency: app.Gateway");
    }

    private static void assertReportedAt(KspResult result, String fileAndLine, String message) {
        assertThat(result.exitCode()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages().lines())
                .as(result.messages())
                .anySatisfy(line -> assertThat(line).contains(fileAndLine).contains(message));
    }
}
