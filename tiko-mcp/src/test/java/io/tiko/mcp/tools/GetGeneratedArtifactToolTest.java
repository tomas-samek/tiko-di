package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GetGeneratedArtifactToolTest {

    @Test
    void factoryKindReturnsPathAndSummaryWhenFileExists(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.OrderRepository","simpleName":"OrderRepository",
                    "scope":"SINGLETON","requiresProxy":false}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        writeGenerated(root, "OrderRepositoryFactory.java", "// 2 lines\nclass X {}\n");

        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "FACTORY", "componentFqn", "x.OrderRepository"));

        assertThat(out)
                .containsEntry("kind", "FACTORY")
                .containsEntry("componentFqn", "x.OrderRepository")
                .containsEntry("exists", true);
        assertThat(out.get("path").toString()).endsWith("io/tiko/generated/OrderRepositoryFactory.java");
        assertThat((Long) out.get("lines")).isGreaterThan(0L);
        assertThat(out).doesNotContainKey("contents");
    }

    @Test
    void proxyKindReturnsPathForProxiedComponent(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.OrderRepository","simpleName":"OrderRepository",
                    "scope":"SINGLETON","requiresProxy":true}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        writeGenerated(root, "OrderRepositoryProxy.java", "class X {}\n");

        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "PROXY", "componentFqn", "x.OrderRepository"));

        assertThat(out).containsEntry("exists", true);
        assertThat(out.get("path").toString()).endsWith("io/tiko/generated/OrderRepositoryProxy.java");
    }

    @Test
    void proxyKindReturnsStructuredNotProxiedReason(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.OrderService","simpleName":"OrderService",
                    "scope":"SINGLETON","requiresProxy":false}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "PROXY", "componentFqn", "x.OrderService"));

        assertThat(out).containsEntry("exists", false).containsEntry("componentFqn", "x.OrderService");
        assertThat(out.get("reason").toString()).contains("not proxied");
    }

    @Test
    void containerKindLocatesHashSuffixedFile(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"TikoContainerImpl_abc12345",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        writeGenerated(root, "TikoContainerImpl_abc12345.java", "// container\nclass C {}\n");

        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        // No componentFqn — container is project-singular.
        var out = tool.execute(Map.of("kind", "CONTAINER"));

        assertThat(out).containsEntry("exists", true).containsEntry("kind", "CONTAINER");
        assertThat(out.get("path").toString()).endsWith("TikoContainerImpl_abc12345.java");
    }

    @Test
    void eventRegistryKindLocatesHashSuffixedFile(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"TikoContainerImpl_abc12345",
                 "components":[],"factoryMethods":[],
                 "eventHandlers":[{"declaringClass":"x.H","methodName":"on","eventType":"x.E","async":false,"hasEventWrapper":false}],
                 "eventTriggers":[],"configurations":[]}
                """);
        writeGenerated(root, "EventRegistry_abc12345.java", "class R {}\n");

        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "EVENT_REGISTRY"));

        assertThat(out).containsEntry("exists", true);
        assertThat(out.get("path").toString()).endsWith("EventRegistry_abc12345.java");
    }

    @Test
    void configBinderKindLooksInConfigSubdir(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],
                 "configurations":[{"qualifiedName":"x.DbConfig","prefix":"db","fields":[]}]}
                """);
        writeGeneratedConfig(root, "DbConfigBinder.java", "class B {}\n");

        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "CONFIG_BINDER", "componentFqn", "x.DbConfig"));

        assertThat(out).containsEntry("exists", true);
        assertThat(out.get("path").toString()).endsWith("io/tiko/generated/config/DbConfigBinder.java");
    }

    @Test
    void fileMissingOnDiskReturnsStructuredReason(@TempDir Path root) throws Exception {
        // Topology says the component exists but no generated source on disk yet.
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.Foo","simpleName":"Foo","scope":"SINGLETON","requiresProxy":false}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "FACTORY", "componentFqn", "x.Foo"));

        assertThat(out).containsEntry("exists", false);
        assertThat(out.get("reason").toString()).contains("not found on disk");
    }

    @Test
    void unknownComponentReturnsStructuredNotFound(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var out = tool.execute(Map.of("kind", "FACTORY", "componentFqn", "x.NeverRegistered"));

        assertThat(out).containsEntry("exists", false);
        assertThat(out.get("reason").toString()).contains("Component not found");
    }

    @Test
    void missingKindThrows(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var args = Map.<String, Object>of();

        assertThatThrownBy(() -> tool.execute(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void invalidKindThrows(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var args = Map.<String, Object>of("kind", "SOMETHING_ELSE");

        assertThatThrownBy(() -> tool.execute(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid kind");
    }

    @Test
    void missingComponentFqnForComponentKeyedKindThrows(@TempDir Path root) throws Exception {
        writeTopology(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new GetGeneratedArtifactTool(TopologyStore.loadFrom(root));
        var args = Map.<String, Object>of("kind", "FACTORY");

        assertThatThrownBy(() -> tool.execute(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("componentFqn");
    }

    private void writeTopology(Path root, String json) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
    }

    private void writeGenerated(Path root, String fileName, String content) throws Exception {
        var f = root.resolve("m/target/generated-sources/annotations/io/tiko/generated/" + fileName);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
    }

    private void writeGeneratedConfig(Path root, String fileName, String content) throws Exception {
        var f = root.resolve("m/target/generated-sources/annotations/io/tiko/generated/config/" + fileName);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
    }
}
