package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.RecordingCodeGenerator;
import io.tiko.processor.core.ksp.RecordingCodeGenerator.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every output names the sources it is derived from, so incremental KSP rebuilds it from all of
 * them: aggregating outputs (container, registry, descriptors) every component, producer and
 * handler owner; a factory only its own component.
 */
class KspOriginatingFilesTest {

    @Test
    void outputsNameTheFilesTheyDeriveFrom() {
        var clock = KspCompiler.kotlin(
                "Clock.kt",
                "package app",
                "",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "class Clock");
        var ticker = KspCompiler.kotlin(
                "Ticker.kt",
                "package app",
                "",
                "data class Tick(val count: Int)",
                "",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "class Ticker(val clock: Clock) {",
                "    @io.tiko.annotations.EventHandler",
                "    fun onTick(tick: Tick) {}",
                "}");
        var producers = KspCompiler.kotlin(
                "Producers.kt",
                "package app",
                "",
                "class Name(val value: String)",
                "",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "class Producers {",
                "    @io.tiko.annotations.Produces(scope = io.tiko.Scope.SINGLETON)",
                "    fun name(): Name = Name(\"tiko\")",
                "}");
        var requests = new ArrayList<Request>();

        var result = KspCompiler.compile(
                List.of(RecordingCodeGenerator.recording(new TikoSymbolProcessorProvider(), requests)),
                Map.of(),
                clock,
                ticker,
                producers);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(requests)
                .extracting(Request::path)
                .anyMatch(path -> path.startsWith("io/tiko/generated/TikoContainerImpl_"))
                .anyMatch(path -> path.startsWith("io/tiko/generated/EventRegistry"))
                .contains("META-INF/tiko/topology.json", "META-INF/tiko/container.properties");
        assertThat(requests)
                .filteredOn(request -> request.path().endsWith("Factory.java"))
                .extracting(Request::path, Request::aggregating, Request::originatingFiles)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "io/tiko/generated/ClockFactory.java", false, List.of("Clock.kt")),
                        org.assertj.core.groups.Tuple.tuple(
                                "io/tiko/generated/TickerFactory.java", false, List.of("Ticker.kt")),
                        org.assertj.core.groups.Tuple.tuple(
                                "io/tiko/generated/ProducersFactory.java", false, List.of("Producers.kt")));
        assertThat(requests)
                .filteredOn(request -> !request.path().endsWith("Factory.java"))
                .isNotEmpty()
                .allSatisfy(request -> {
                    assertThat(request.aggregating()).as(request.path()).isTrue();
                    assertThat(request.originatingFiles())
                            .as(request.path())
                            .containsExactly("Clock.kt", "Producers.kt", "Ticker.kt");
                });
    }
}
