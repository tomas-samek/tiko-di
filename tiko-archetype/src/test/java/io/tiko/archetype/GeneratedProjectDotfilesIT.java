package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards #557: a scaffolded project gets its dotfiles under their real names.
 *
 * <p>Runs after {@code archetype:integration-test} (both bind to the {@code integration-test} phase;
 * the packaging's default binding goes first), which generates
 * {@code src/test/resources/projects/basic} into {@code target/test-classes/projects/basic/project}.
 */
class GeneratedProjectDotfilesIT {

    private static final Path GENERATED = Path.of("target", "test-classes", "projects", "basic", "project", "basic-it");
    private static final Path TEMPLATE = Path.of("src", "main", "resources", "archetype-resources");

    static Stream<Arguments> dotfiles() {
        return Stream.of(Arguments.of(".gitignore"), Arguments.of(".mcp.json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dotfiles")
    void generatedProjectHoldsTheDotfileVerbatim(String name) throws IOException {
        var generated = GENERATED.resolve(name);

        assertThat(generated).as("%s in the generated project", name).isRegularFile();
        assertThat(Files.readString(generated)).isEqualTo(Files.readString(TEMPLATE.resolve(name)));
        assertThat(GENERATED.resolve(name.substring(1)))
                .as("no undotted %s left behind", name)
                .doesNotExist();
    }

    static Stream<Arguments> agentFiles() {
        return Stream.of(
                Arguments.of("CLAUDE.md"),
                Arguments.of("AGENTS.md"),
                Arguments.of(".cursor/rules/tiko.md"),
                Arguments.of(".github/copilot-instructions.md"),
                Arguments.of(".junie/guidelines.md"));
    }

    /**
     * Guards #702: a Markdown file run through the archetype's Velocity filter loses every line that
     * starts with {@code ##}, which Velocity reads as a comment, so its headings disappear.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("agentFiles")
    void generatedProjectHoldsTheAgentFileVerbatim(String name) throws IOException {
        var generated = GENERATED.resolve(name);

        assertThat(generated).as("%s in the generated project", name).isRegularFile();
        assertThat(Files.readString(generated))
                .as("%s in the generated project, against its template", name)
                .isEqualTo(Files.readString(TEMPLATE.resolve(name)));
    }
}
