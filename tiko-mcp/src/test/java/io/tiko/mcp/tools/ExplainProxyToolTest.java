package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplainProxyToolTest {

    @Test
    void returnsProxyDetailForProxiedComponent(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.OrderRepository","scope":"SINGLETON",
                    "requiresProxy":true,
                    "proxy":{
                      "interface":"x.Orders",
                      "proxiedMethods":["save","find"],
                      "reason":"SINGLETON_INJECTS_EVENT"}}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainProxyTool(store);
        var out = tool.execute(Map.of("componentFqn", "x.OrderRepository"));

        assertThat(out)
                .containsEntry("componentFqn", "x.OrderRepository")
                .containsEntry("proxied", true)
                .containsEntry("interface", "x.Orders")
                .containsEntry("reason", "SINGLETON_INJECTS_EVENT");

        @SuppressWarnings("unchecked")
        var methods = (List<String>) out.get("proxiedMethods");
        assertThat(methods).containsExactly("save", "find");
    }

    @Test
    void returnsProxiedFalseForNonProxiedComponent(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.OrderService","scope":"SINGLETON","requiresProxy":false}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainProxyTool(store);
        var out = tool.execute(Map.of("componentFqn", "x.OrderService"));

        assertThat(out).containsEntry("componentFqn", "x.OrderService").containsEntry("proxied", false);
        // No interface / proxiedMethods / reason keys when not proxied — definite-not-proxied beats fabricated nulls.
        assertThat(out).doesNotContainKeys("interface", "proxiedMethods", "reason");
    }

    @Test
    void unknownComponentReturnsStructuredNotFoundResponse(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainProxyTool(store);
        var out = tool.execute(Map.of("componentFqn", "x.NeverRegistered"));

        // Structured "not found" — small definite answer, not an exception.
        assertThat(out).containsEntry("componentFqn", "x.NeverRegistered").containsEntry("found", false);
    }

    @Test
    void missingComponentFqnArgThrows(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainProxyTool(store);
        var args = Map.<String, Object>of();

        assertThatThrownBy(() -> tool.execute(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("componentFqn");
    }

    @Test
    void proxiedComponentWithoutProxyBlockSurfacesHonestlyAsNulls(@TempDir Path root) throws Exception {
        // Older topology snapshot: requiresProxy=true but no `proxy` detail block. The tool
        // must not fabricate values it doesn't have — it surfaces what's known + explicit nulls.
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.Legacy","scope":"SINGLETON","requiresProxy":true}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainProxyTool(store);
        var out = tool.execute(Map.of("componentFqn", "x.Legacy"));

        assertThat(out)
                .containsEntry("proxied", true)
                .containsEntry("interface", null)
                .containsEntry("reason", null);
        @SuppressWarnings("unchecked")
        var methods = (List<String>) out.get("proxiedMethods");
        assertThat(methods).isEmpty();
    }

    private TopologyStore storeWith(Path root, String json) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
