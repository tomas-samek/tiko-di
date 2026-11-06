package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.RoundWatcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Component} another processor generates in a later KSP round is still wired into the
 * one container, together with the components of the first round (Review Focus 1).
 */
class KspRoundingTest {

    @Test
    void componentGeneratedByAnotherProcessorIsWired() {
        var handWritten = KspCompiler.kotlin(
                "Clock.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Clock");

        var result = KspCompiler.compile(
                List.of(new GeneratesComponent(), new TikoSymbolProcessorProvider()), Map.of(), handWritten);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        var container = result.generatedJavaNames().stream()
                .filter(name -> name.startsWith("io.tiko.generated.TikoContainerImpl_"))
                .findFirst()
                .flatMap(result::generatedJava);
        assertThat(container).as("generated container").isPresent();
        assertThat(container.get()).contains("TickerFactory").contains("ClockFactory");
    }

    /**
     * The trigger: the first round whose new files carry no entry point, after one that did, is
     * where the pipeline finishes, so its output arrives as the next round's new files.
     */
    @Test
    void finishesInTheFirstRoundWithoutEntryPoints() {
        var handWritten =
                KspCompiler.kotlin("Clock.kt", "package app", "", "@io.tiko.annotations.Component", "class Clock");
        var watcher = new RoundWatcher();

        var result = KspCompiler.compile(List.of(watcher, new TikoSymbolProcessorProvider()), Map.of(), handWritten);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(watcher.newFilesPerRound()).as("new files per round").hasSizeGreaterThanOrEqualTo(3);
        assertThat(watcher.newFilesPerRound().get(1)).containsExactly("Plain.kt");
        assertThat(watcher.newFilesPerRound().get(2)).anyMatch(name -> name.startsWith("TikoContainerImpl_"));
    }

    /**
     * Writes, in its first round, a Kotlin {@code @Component} that depends on the hand-written
     * one: KSP hands it to the Tiko processor only in the second round.
     */
    private static final class GeneratesComponent implements SymbolProcessorProvider {

        @Override
        public SymbolProcessor create(com.google.devtools.ksp.processing.SymbolProcessorEnvironment environment) {
            return new SymbolProcessor() {
                private boolean generated;

                @Override
                public List<KSAnnotated> process(Resolver resolver) {
                    if (generated) {
                        return List.of();
                    }
                    generated = true;
                    var source = String.join(
                            "\n",
                            "package app",
                            "",
                            "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                            "class Ticker(val clock: Clock)",
                            "");
                    try (var out = environment
                            .getCodeGenerator()
                            .createNewFile(new Dependencies(false), "app", "Ticker", "kt")) {
                        out.write(source.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return List.of();
                }
            };
        }
    }
}
