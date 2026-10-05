package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.mcp.Symlinks;
import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code get_generated_artifact} neither walks into symlinked directories nor reports symlinked
 * files that lead outside the project root (#475).
 */
class GetGeneratedArtifactToolSymlinkTest {

    private static final String GENERATED = "target/generated-sources/annotations/io/tiko/generated/";

    @Test
    void symlinkedDirectoryLeadingOutsideTheRootIsNotWalked(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        var outsideModule = Files.createDirectories(tmp.resolve("outside/module"));
        var outsideContainer = outsideModule.resolve(GENERATED + "TikoContainerImpl_abc.java");
        Files.createDirectories(outsideContainer.getParent());
        Files.writeString(outsideContainer, "class TikoContainerImpl_abc {}\n", StandardCharsets.UTF_8);
        Symlinks.linkOrSkip(root.resolve("linked-module"), outsideModule);

        var out = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root)).execute(Map.of("kind", "CONTAINER"));

        assertThat(out)
                .as("a container found only through a link leading outside the project must not be reported")
                .containsEntry("exists", false);
    }

    @Test
    void symlinkedFileLeadingOutsideTheRootIsNotReported(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        var tikoDir = Files.createDirectories(root.resolve("target/classes/META-INF/tiko"));
        Files.writeString(tikoDir.resolve("topology.json"), """
                {"components":[{"qualifiedName":"x.OrderRepository","simpleName":"OrderRepository",
                  "scope":"SINGLETON","requiresProxy":false}]}
                """, StandardCharsets.UTF_8);
        var outsideFile = Files.createDirectories(tmp.resolve("outside")).resolve("secret.java");
        Files.writeString(outsideFile, "line 1\nline 2\n", StandardCharsets.UTF_8);
        Symlinks.linkOrSkip(root.resolve(GENERATED + "OrderRepositoryFactory.java"), outsideFile);

        var out = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root))
                .execute(Map.of("kind", "FACTORY", "componentFqn", "x.OrderRepository"));

        assertThat(out)
                .as("a generated-source name that links outside the project must not be stat'ed or reported")
                .containsEntry("exists", false)
                .doesNotContainKey("lines");
    }
}
