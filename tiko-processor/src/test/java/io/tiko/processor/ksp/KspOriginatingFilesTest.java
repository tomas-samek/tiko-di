package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.RecordingCodeGenerator;
import io.tiko.processor.core.ksp.RecordingCodeGenerator.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /**
     * Outputs beyond the factories and the container: a proxy is derived from the class (or the
     * factory's declaring class) it stands in for, the config binder and its nested-record coercer
     * from the {@code @Configuration} file, and the binder service entry aggregates the configs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedOutputs")
    void derivedOutputsNameTheirOwnSources(String name, String pathPrefix, boolean aggregating, List<String> origins) {
        var requests = new ArrayList<Request>();

        var result = KspCompiler.compile(
                List.of(RecordingCodeGenerator.recording(new TikoSymbolProcessorProvider(), requests)),
                Map.of(),
                KspCompiler.kotlin(
                        "Greeter.kt",
                        "package app",
                        "",
                        "interface Greeter { fun greet(): String }",
                        "",
                        "@io.tiko.annotations.Component(scope = io.tiko.Scope.EVENT)",
                        "class EventGreeter : Greeter {",
                        "    override fun greet(): String = \"hi\"",
                        "}"),
                KspCompiler.kotlin(
                        "Conns.kt",
                        "package app",
                        "",
                        "interface Conn { fun id(): String }",
                        "",
                        "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                        "class Conns {",
                        "    @io.tiko.annotations.Produces(scope = io.tiko.Scope.EVENT)",
                        "    fun conn(): Conn = object : Conn { override fun id() = \"c\" }",
                        "}"),
                KspCompiler.kotlin(
                        "User.kt",
                        "package app",
                        "",
                        "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                        "class User(val greeter: Greeter, val conn: Conn)"),
                KspCompiler.kotlin(
                        "Cfg.kt",
                        "package app",
                        "",
                        "@JvmRecord data class Pool(val size: Int)",
                        "@io.tiko.annotations.Configuration(prefix = \"app\")",
                        "@JvmRecord data class AppConfig(val host: String, val pool: Pool)",
                        "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                        "class Server(val config: AppConfig)"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(requests)
                .filteredOn(request -> request.path().startsWith(pathPrefix))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.aggregating()).as("aggregating").isEqualTo(aggregating);
                    assertThat(request.originatingFiles()).containsExactlyElementsOf(origins);
                });
    }

    static Stream<Arguments> derivedOutputs() {
        return Stream.of(
                Arguments.of("componentProxy", "io/tiko/generated/EventGreeterProxy", false, List.of("Greeter.kt")),
                Arguments.of("producesProxy", "io/tiko/generated/Conns_connProxy", false, List.of("Conns.kt")),
                Arguments.of("configBinder", "io/tiko/generated/config/AppConfigBinder", false, List.of("Cfg.kt")),
                Arguments.of(
                        "nestedRecordCoercer", "io/tiko/generated/config/PoolNestedCoercer_", false, List.of("Cfg.kt")),
                Arguments.of(
                        "configBinderServiceEntry",
                        "META-INF/services/io.tiko.config.ConfigBinder",
                        true,
                        List.of("Cfg.kt")),
                Arguments.of("configSchema", "META-INF/tiko/config-schema.json", true, List.of("Cfg.kt")));
    }
}
