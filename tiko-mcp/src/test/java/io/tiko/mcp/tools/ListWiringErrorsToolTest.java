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

class ListWiringErrorsToolTest {

    @Test
    void returnsEmptyListWhenFileMissing(@TempDir Path root) throws Exception {
        var topo = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(topo.getParent());
        Files.writeString(topo, """
                        {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],
                         "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                        """, StandardCharsets.UTF_8);
        var tool = new ListWiringErrorsTool(TopologyStore.loadFrom(root));

        var result = tool.execute(Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).isEmpty();
        assertThat(result.keySet()).containsExactly("errors");
    }

    @Test
    void returnsErrorsFromFile(@TempDir Path root) throws Exception {
        var dir = root.resolve("m/target/classes/META-INF/tiko/");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("topology.json"), """
                        {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],
                         "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                        """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("wiring-errors.json"), """
                        {"errors":[
                          {"kind":"MISSING_DEPENDENCY","sourceFile":"src/main/java/X.java","line":17,
                           "componentFqn":"example.X","message":"No component provides Y",
                           "suggestedFix":"Add @Component to Y"}
                        ]}
                        """, StandardCharsets.UTF_8);

        var tool = new ListWiringErrorsTool(TopologyStore.loadFrom(root));
        var result = tool.execute(Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("kind")).isEqualTo("MISSING_DEPENDENCY");
        assertThat(result.keySet()).containsExactly("errors");
    }
}
