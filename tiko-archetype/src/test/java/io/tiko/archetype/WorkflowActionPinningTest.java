package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every third-party action in the workflows is pinned to an immutable commit SHA, with its
 * version kept readable in a trailing comment, and Dependabot proposes the updates (SEC-10, #477).
 */
class WorkflowActionPinningTest {

    /** Relative to the {@code tiko-archetype} module directory. */
    private static final Path GITHUB = Path.of("..", ".github");

    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*(\\S+)(.*)$");

    /** {@code owner/repo[/path]@<40-hex sha> # v<version>}. */
    private static final Pattern PINNED = Pattern.compile("[\\w.-]+/[\\w./-]+@[0-9a-f]{40}");

    private static final Pattern VERSION_COMMENT = Pattern.compile("\\s+#\\s*v\\d+(?:\\.\\d+)*\\s*");

    static Stream<Arguments> actionReferences() throws IOException {
        try (var files = Files.list(GITHUB.resolve("workflows"))) {
            return files
                    .filter(f -> f.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .flatMap(WorkflowActionPinningTest::usesLines)
                    .toList()
                    .stream();
        }
    }

    private static Stream<Arguments> usesLines(Path workflow) {
        try {
            var lines = Files.readAllLines(workflow);
            return IntStream.range(0, lines.size())
                    .mapToObj(i -> {
                        var m = USES.matcher(lines.get(i));
                        if (!m.matches() || m.group(1).startsWith("./")) return null;
                        return Arguments.of(
                                workflow.getFileName() + ":" + (i + 1) + " " + m.group(1), m.group(1), m.group(2));
                    })
                    .filter(Objects::nonNull);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actionReferences")
    void actionIsPinnedToACommitShaWithItsVersionInAComment(String where, String reference, String rest) {
        assertThat(reference)
                .as("%s: pin third-party actions to a full commit SHA, not a movable tag", where)
                .matches(PINNED);
        assertThat(rest)
                .as("%s: keep the pinned version readable as a trailing '# vX.Y.Z' comment", where)
                .matches(VERSION_COMMENT);
    }

    @Test
    void dependabotProposesActionUpdates() throws IOException {
        var config = GITHUB.resolve("dependabot.yml");
        assertThat(config)
                .as(".github/dependabot.yml keeps SHA-pinned actions up to date")
                .exists();

        assertThat(Files.readString(config))
                .containsPattern("package-ecosystem:\\s*\"?github-actions\"?")
                .containsPattern("directory:\\s*\"?/\"?");
    }
}
