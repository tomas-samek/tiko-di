package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Every resolved dependency, transitive and bundled ones included, reaches GitHub's dependency
 * graph (SEC-9, #492). GitHub's static pom parsing sees only declared dependencies; the jars
 * {@code tiko-mcp} bundles (MCP SDK, reactor, json-schema-validator, …) reach Dependabot only
 * through the snapshot this workflow submits on every push to {@code main}.
 */
class DependencySubmissionWorkflowTest {

    /** Relative to the {@code tiko-archetype} module directory. */
    private static final Path WORKFLOW = Path.of("..", ".github", "workflows", "dependency-submission.yml");

    @Test
    void submitsTheResolvedMavenTreeOnEveryPushToMain() throws IOException {
        assertThat(WORKFLOW)
                .as("the dependency-submission workflow keeps bundled jars visible")
                .exists();
        var workflow = Files.readString(WORKFLOW);

        assertThat(workflow)
                .as("runs on pushes to main, so the default branch's graph is current")
                .containsPattern("(?ms)^on:.*^  push:\\s*\\n\\s+branches:\\s*\\[\\s*\"?main\"?\\s*]")
                .contains("advanced-security/maven-dependency-submission-action@");
    }

    @Test
    void grantsWriteOnlyToTheSubmittingJob() throws IOException {
        var workflow = Files.readString(WORKFLOW);

        assertThat(workflow)
                .as("no workflow-wide write permission; the job asks for contents: write itself")
                .containsPattern("(?m)^permissions:\\s*\\{}\\s*$")
                .containsPattern("(?m)^    permissions:\\s*\\n\\s+contents:\\s*write\\s*$");
    }

    @Test
    void noModuleIsLeftOutOfTheSnapshot() throws IOException {
        assertThat(Files.readString(WORKFLOW))
                .as("excluding a module (e.g. -pl !tiko-mcp) would hide what it bundles again")
                .doesNotContainPattern("-pl\\s|--projects");
    }
}
