package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigSources;
import io.tiko.generated.config.KafkaConfigBinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #536: every {@code tiko.kafka} YAML snippet the docs teach must bind against the shipped
 * {@link KafkaConfig} — a user who copies one into {@code application.yaml} must not get
 * unknown-key errors at startup.
 */
class KafkaDocsYamlBindsTest {

    private static final List<Path> DOCS = List.of(
            Path.of("..", "docs", "cookbooks", "kafka.md"),
            Path.of("..", ".ai-skills", "tiko-build", "reference", "kafka.md"),
            Path.of("..", ".ai-skills", "tiko-build", "reference", "api-signatures.md"));

    static Stream<Arguments> kafkaYamlSnippets() throws IOException {
        List<Arguments> snippets = new ArrayList<>();
        for (Path doc : DOCS) {
            for (String yaml : yamlBlocks(Files.readAllLines(doc))) {
                if (yaml.contains("tiko:") && yaml.contains("kafka:")) {
                    snippets.add(Arguments.of(doc.getFileName() + " @ " + firstLine(yaml), yaml));
                }
            }
        }
        return snippets.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kafkaYamlSnippets")
    void documentedKafkaYamlBinds(String where, String yaml, @TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yaml"), yaml);
        var ctx = new BindContext("application.yaml");

        new KafkaConfigBinder().bind(ConfigSources.file(file).load(), ctx);

        assertThat(ctx.issues()).as("binding the snippet at %s", where).isEmpty();
    }

    /** The bodies of ```yaml fences, with the fence's own indentation removed (fences inside lists). */
    private static List<String> yamlBlocks(List<String> lines) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        int indent = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (current == null && trimmed.equals("```yaml")) {
                current = new StringBuilder();
                indent = line.indexOf('`');
            } else if (current != null && trimmed.equals("```")) {
                blocks.add(current.toString());
                current = null;
            } else if (current != null) {
                current.append(line.length() >= indent ? line.substring(indent) : line.strip())
                        .append('\n');
            }
        }
        return blocks;
    }

    private static String firstLine(String yaml) {
        return yaml.lines()
                .filter(l -> !l.isBlank())
                .skip(2)
                .findFirst()
                .orElse("")
                .strip();
    }
}
