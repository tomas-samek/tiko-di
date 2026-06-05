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

class TopologyOverviewToolTest {

    @Test
    void emptyTopologyReturnsZeroedTotalsAndDefaultSuggestions(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new TopologyOverviewTool(store);
        var out = tool.execute(Map.of());

        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) out.get("totals");
        assertThat(totals)
                .containsEntry("components", 0)
                .containsEntry("factoryMethods", 0)
                .containsEntry("eventHandlers", 0)
                .containsEntry("eventTriggers", 0)
                .containsEntry("configurations", 0)
                .containsEntry("wiringErrors", 0);
        assertThat(out.get("hasProfileConflicts")).isEqualTo(false);
        assertThat(out.get("loadedAt")).isInstanceOf(String.class);

        @SuppressWarnings("unchecked")
        var scopes = (Map<String, Object>) out.get("scopes");
        assertThat(scopes).isEmpty();

        @SuppressWarnings("unchecked")
        var suggested = (List<String>) out.get("suggestedNextQueries");
        // Empty / clean topology — the default discovery trio.
        assertThat(suggested).containsExactly("list_components", "list_events", "list_lifecycle_hooks");
    }

    @Test
    void populatedTopologyAggregatesScopesAndTotals(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.A","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"x.B","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"x.C","scope":"EVENT","interfaces":[]},
                   {"qualifiedName":"x.D","scope":"PROTOTYPE","interfaces":[]}
                 ],
                 "factoryMethods":[
                   {"declaringClass":"x.B","methodName":"build","returnType":"x.Conn","scope":"SINGLETON"}
                 ],
                 "eventHandlers":[
                   {"eventType":"x.E1","method":"on","componentFqn":"x.A"},
                   {"eventType":"x.E2","method":"on","componentFqn":"x.B"}
                 ],
                 "eventTriggers":[{"trigger":"x.T1"}],
                 "configurations":[{"qualifiedName":"x.Cfg","prefix":"x"}]}
                """);
        var tool = new TopologyOverviewTool(store);
        var out = tool.execute(Map.of());

        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) out.get("totals");
        assertThat(totals)
                .containsEntry("components", 4)
                .containsEntry("factoryMethods", 1)
                .containsEntry("eventHandlers", 2)
                .containsEntry("eventTriggers", 1)
                .containsEntry("configurations", 1)
                .containsEntry("wiringErrors", 0);

        @SuppressWarnings("unchecked")
        var scopes = (Map<String, Object>) out.get("scopes");
        // 3 SINGLETONs (2 components + 1 factory) + 1 EVENT + 1 PROTOTYPE.
        assertThat(scopes)
                .containsEntry("SINGLETON", 3)
                .containsEntry("EVENT", 1)
                .containsEntry("PROTOTYPE", 1);
    }

    @Test
    void wiringErrorsPushLitstErrorsToFirstSuggestion(@TempDir Path root) throws Exception {
        var store = storeWithErrors(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, """
                {"errors":[{"kind":"MISSING_DEPENDENCY","message":"foo"}]}
                """);
        var tool = new TopologyOverviewTool(store);
        var out = tool.execute(Map.of());

        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) out.get("totals");
        assertThat(totals).containsEntry("wiringErrors", 1);

        @SuppressWarnings("unchecked")
        var suggested = (List<String>) out.get("suggestedNextQueries");
        assertThat(suggested).first().isEqualTo("list_wiring_errors");
    }

    @Test
    void profileConflictsPushListProfileConflictsToFirstSuggestion(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["dev"]},
                   {"qualifiedName":"x.ProdImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["prod"]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new TopologyOverviewTool(store);
        var out = tool.execute(Map.of());

        assertThat(out.get("hasProfileConflicts")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        var suggested = (List<String>) out.get("suggestedNextQueries");
        assertThat(suggested).first().isEqualTo("list_profile_conflicts");
    }

    @Test
    void wiringErrorsOutrankProfileConflictsInSuggestionOrdering(@TempDir Path root) throws Exception {
        // When both signals fire, errors are the more urgent thing — they fail the build, conflicts don't.
        var store = storeWithErrors(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["dev"]},
                   {"qualifiedName":"x.ProdImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["prod"]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, """
                {"errors":[{"kind":"MISSING_DEPENDENCY","message":"foo"}]}
                """);
        var tool = new TopologyOverviewTool(store);
        var out = tool.execute(Map.of());

        @SuppressWarnings("unchecked")
        var suggested = (List<String>) out.get("suggestedNextQueries");
        assertThat(suggested).first().isEqualTo("list_wiring_errors");
        assertThat(out.get("hasProfileConflicts")).isEqualTo(true);
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }

    private TopologyStore storeWithErrors(Path root, String topologyJson, String wiringErrorsJson) throws Exception {
        var topology = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(topology.getParent());
        Files.writeString(topology, topologyJson, StandardCharsets.UTF_8);
        var errors = root.resolve("m/target/classes/META-INF/tiko/wiring-errors.json");
        Files.writeString(errors, wiringErrorsJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
