package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListProfileConflictsToolTest {

    @Test
    void detectsSameInterfaceUnderDifferentProfiles(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["dev"]},
                   {"qualifiedName":"x.ProdImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["prod"]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListProfileConflictsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts =
                (List<Map<String, Object>>) tool.execute(Map.of()).get("conflicts");
        assertThat(conflicts).hasSize(1);
        var c = conflicts.get(0);
        assertThat(c.get("type")).isEqualTo("x.IThing");
        @SuppressWarnings("unchecked")
        var impls = (List<Map<String, Object>>) c.get("implementations");
        assertThat(impls).hasSize(2);
    }

    @Test
    void noConflictsWhenAllShareProfile(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":[]},
                   {"qualifiedName":"x.B","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":[]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListProfileConflictsTool(store);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts =
                (List<Map<String, Object>>) tool.execute(Map.of()).get("conflicts");
        assertThat(conflicts).isEmpty();
    }

    @Test
    void wildcardPlusPinnedIsNotAConflict(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.DefaultImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":[]},
                   {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["dev"]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListProfileConflictsTool(store);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts =
                (List<Map<String, Object>>) tool.execute(Map.of()).get("conflicts");
        assertThat(conflicts).isEmpty();
    }

    private TopologyStore storeWith(Path root, String json) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
