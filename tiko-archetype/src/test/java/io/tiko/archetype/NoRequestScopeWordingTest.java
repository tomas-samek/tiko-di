package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #526: the scope model has three scopes (SINGLETON / EVENT / PROTOTYPE) since #250/#251, yet
 * docs, examples and comments kept describing a REQUEST scope for months. This gate fails when
 * user-facing prose, example code or framework comments mention one again. Dated records that
 * describe the model as it stood are excluded.
 */
class NoRequestScopeWordingTest {

    private static final Path REPO = Path.of("..");

    /** The retired scope's names and API, as they appeared before #250/#251. */
    private static final Pattern REQUEST_SCOPE = Pattern.compile(
            "request[- ]?scope|Scope\\.REQUEST|REQUEST/EVENT|(?-i:REQUEST →)|REQUEST-vs-EVENT"
                    // #535: the retired lifecycle pair (not handler names like onRequestStarted),
                    // a scope-matrix table cell, and the old nesting phrase.
                    + "|(?<!on)Request(Started|Ending)|(?-i:\\|\\s*REQUEST\\s*\\|)|(?-i:one REQUEST)",
            Pattern.CASE_INSENSITIVE);

    /** Dated or historical records, and this gate itself. */
    private static final Set<String> EXCLUDED = Set.of(
            "docs/superpowers/",
            "docs/skill-benchmark/",
            "docs/roadmap.md",
            ".ai-skills/tiko-architect/SKILL.md",
            "tiko-processor/src/test/java/io/tiko/processor/scopes/CrossScopeMatrixTest.java",
            "tiko-archetype/src/test/java/io/tiko/archetype/NoRequestScopeWordingTest.java");

    @Test
    void docsExamplesAndSourcesDescribeNoRequestScope() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : scannedFiles()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (REQUEST_SCOPE.matcher(lines.get(i)).find()) {
                    hits.add(
                            relative(file) + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertThat(hits).as("mentions of the retired REQUEST scope").isEmpty();
    }

    private static List<Path> scannedFiles() throws IOException {
        List<Path> roots = new ArrayList<>(List.of(
                REPO.resolve("docs"),
                REPO.resolve("tiko-examples"),
                REPO.resolve(".ai-skills"),
                REPO.resolve("README.md"),
                REPO.resolve("CLAUDE.md")));
        try (Stream<Path> modules = Files.list(REPO)) {
            modules.filter(m -> m.getFileName().toString().startsWith("tiko-"))
                    .map(m -> m.resolve("src"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        }
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().matches(".*\\.(md|java|xml|ya?ml)$"))
                        .filter(f -> !relative(f).contains("/target/"))
                        .filter(f -> !f.getFileName().toString().equals("dependency-reduced-pom.xml")) // shade output
                        .filter(f -> EXCLUDED.stream().noneMatch(relative(f)::startsWith))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static String relative(Path file) {
        return REPO.relativize(file).toString().replace('\\', '/');
    }
}
