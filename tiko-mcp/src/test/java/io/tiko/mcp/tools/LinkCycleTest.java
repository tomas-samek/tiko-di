package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.tiko.mcp.Symlinks;
import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A directory link under the project that points back at one of its ancestors doesn't stop
 * {@code tiko-mcp} from starting or from finding generated files (#499).
 */
class LinkCycleTest {

    @TempDir
    Path root;

    @BeforeEach
    void projectWithALinkBackToItsRoot() throws Exception {
        var topology = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(topology.getParent());
        Files.writeString(topology, """
                {"schemaVersion":1,"module":"m",
                 "components":[{"qualifiedName":"x.OrderRepository","simpleName":"OrderRepository",
                                "scope":"SINGLETON","requiresProxy":false}],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, StandardCharsets.UTF_8);
        var factory =
                root.resolve("m/target/generated-sources/annotations/io/tiko/generated/OrderRepositoryFactory.java");
        Files.createDirectories(factory.getParent());
        Files.writeString(factory, "class X {}\n", StandardCharsets.UTF_8);

        Symlinks.directoryLinkOrSkip(root.resolve("loop"), root);
    }

    /** Removes the link before @TempDir cleanup, which would otherwise walk into the cycle. */
    @AfterEach
    void removeTheLink() throws Exception {
        Files.deleteIfExists(root.resolve("loop"));
    }

    @Test
    void topologyLoadsDespiteTheCycle() {
        var store = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> TopologyStore.loadFrom(root));

        assertThat(store.components()).extracting(c -> c.get("qualifiedName")).containsExactly("x.OrderRepository");
    }

    @Test
    void generatedArtifactIsFoundDespiteTheCycle() {
        var out = assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> new GetGeneratedArtifactTool(TopologyStore.loadFrom(root))
                        .execute(Map.of("kind", "FACTORY", "componentFqn", "x.OrderRepository")));

        assertThat(out).containsEntry("exists", true);
        assertThat(out.get("path").toString()).doesNotContain("loop");
    }
}
