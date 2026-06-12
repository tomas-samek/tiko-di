package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins reproducible code generation (#334): generated members follow a sorted, stable
 * order (HashMap iteration used to reshuffle the whole container when one component was
 * added — churny diffs, and load-bearing for dispatch-arm precedence), identical inputs
 * yield byte-identical output regardless of source order, and generation happens in the
 * round that carried the annotations — not the final round, where javac emits one
 * "created in the last round" warning per generated file and breaks {@code -Werror}
 * builds.
 *
 * <p>The fixture names are chosen so HashMap bucket order (Mike, Zulu, Alpha on a
 * 16-slot table) differs from alphabetical order — a sorted-emission regression cannot
 * pass by accident.
 */
class DeterministicGenerationTest {

    private static JavaFileObject bean(String name) {
        return JavaFileObjects.forSourceLines(
                "demo." + name,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + name + " {}");
    }

    private static JavaFileObject handler(String name) {
        return JavaFileObjects.forSourceLines(
                "demo." + name,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + name + " {",
                "  @EventHandler",
                "  public void onPing(PingEvent event) {}",
                "}");
    }

    private static JavaFileObject pingEvent() {
        return JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}");
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        JavaFileObject file = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void generatedMembersFollowSortedComponentKeys() throws IOException {
        Compilation c = compile(bean("Zulu"), bean("Alpha"), bean("Mike"));

        CompilationSubject.assertThat(c).succeeded();
        String container = generatedSource(c, "TikoContainerImpl");

        int alpha = container.indexOf("public Alpha getAlpha()");
        int mike = container.indexOf("public Mike getMike()");
        int zulu = container.indexOf("public Zulu getZulu()");
        assertThat(alpha).isPositive();
        assertThat(mike).isGreaterThan(alpha);
        assertThat(zulu).isGreaterThan(mike);
    }

    @Test
    void sourceOrderPermutationProducesIdenticalOutput() throws IOException {
        JavaFileObject[] forward = {pingEvent(), handler("Alpha"), handler("Mike"), bean("Zulu")};
        JavaFileObject[] reversed = {bean("Zulu"), handler("Mike"), handler("Alpha"), pingEvent()};

        Compilation first = compile(forward);
        Compilation second = compile(reversed);

        CompilationSubject.assertThat(first).succeeded();
        CompilationSubject.assertThat(second).succeeded();

        assertThat(generatedSource(second, "TikoContainerImpl")).isEqualTo(generatedSource(first, "TikoContainerImpl"));
        assertThat(generatedSource(second, "EventRegistry")).isEqualTo(generatedSource(first, "EventRegistry"));
    }

    @Test
    void generationDoesNotHappenInTheFinalRound() {
        Compilation c = compile(bean("Alpha"));

        CompilationSubject.assertThat(c).succeeded();
        assertThat(c.diagnostics().stream().map(d -> d.getMessage(null)))
                .as("files created in the final round trigger javac's last-round warning and break -Werror")
                .noneMatch(m -> m.contains("created in the last round"));
    }
}
