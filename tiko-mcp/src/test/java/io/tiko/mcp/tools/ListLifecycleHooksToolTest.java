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

class ListLifecycleHooksToolTest {

    @Test
    void listsAllHooksWhenPhaseFilterAbsent(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON",
                    "lifecycle":{"postConstruct":["start"],"preDestroy":["stop"],"autoCloseable":false}},
                   {"qualifiedName":"x.B","scope":"SINGLETON",
                    "lifecycle":{"postConstruct":["init","warmCache"],"preDestroy":[],"autoCloseable":false}},
                   {"qualifiedName":"x.C","scope":"SINGLETON",
                    "lifecycle":{"postConstruct":[],"preDestroy":[],"autoCloseable":true}}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListLifecycleHooksTool(store);

        @SuppressWarnings("unchecked")
        var hooks = (List<Map<String, Object>>) tool.execute(Map.of()).get("hooks");

        // 4 hooks: A.start (POST), A.stop (PRE), B.init (POST), B.warmCache (POST). C has none.
        assertThat(hooks).hasSize(4);
        // Stable order: by componentFqn, then phase (POST_CONSTRUCT before PRE_DESTROY).
        assertThat(hooks.get(0))
                .containsEntry("componentFqn", "x.A")
                .containsEntry("method", "start")
                .containsEntry("phase", "POST_CONSTRUCT");
        assertThat(hooks.get(1)).containsEntry("componentFqn", "x.A").containsEntry("phase", "PRE_DESTROY");
        assertThat(hooks.get(2)).containsEntry("componentFqn", "x.B").containsEntry("method", "init");
        assertThat(hooks.get(3)).containsEntry("componentFqn", "x.B").containsEntry("method", "warmCache");
    }

    @Test
    void filtersToPostConstructPhase(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON",
                    "lifecycle":{"postConstruct":["start"],"preDestroy":["stop"]}}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListLifecycleHooksTool(store);

        @SuppressWarnings("unchecked")
        var hooks = (List<Map<String, Object>>)
                tool.execute(Map.of("phase", "POST_CONSTRUCT")).get("hooks");

        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0)).containsEntry("phase", "POST_CONSTRUCT").containsEntry("method", "start");
    }

    @Test
    void filtersToPreDestroyPhase(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON",
                    "lifecycle":{"postConstruct":["start"],"preDestroy":["stop"]}}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListLifecycleHooksTool(store);

        @SuppressWarnings("unchecked")
        var hooks = (List<Map<String, Object>>)
                tool.execute(Map.of("phase", "PRE_DESTROY")).get("hooks");

        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0)).containsEntry("phase", "PRE_DESTROY").containsEntry("method", "stop");
    }

    @Test
    void emptyResultWhenNoHooksOnAnyComponent(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON",
                    "lifecycle":{"postConstruct":[],"preDestroy":[],"autoCloseable":true}}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListLifecycleHooksTool(store);
        @SuppressWarnings("unchecked")
        var hooks = (List<Map<String, Object>>) tool.execute(Map.of()).get("hooks");
        assertThat(hooks).isEmpty();
    }

    @Test
    void componentWithoutLifecycleKeyContributesNothing(@TempDir Path root) throws Exception {
        // Older topology snapshots may omit the lifecycle block entirely — the tool must not NPE.
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON"}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListLifecycleHooksTool(store);
        @SuppressWarnings("unchecked")
        var hooks = (List<Map<String, Object>>) tool.execute(Map.of()).get("hooks");
        assertThat(hooks).isEmpty();
    }

    @Test
    void invalidPhaseThrowsIllegalArgument(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListLifecycleHooksTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("phase", "WHENEVER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid phase: WHENEVER");
    }

    private TopologyStore storeWith(Path root, String json) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
