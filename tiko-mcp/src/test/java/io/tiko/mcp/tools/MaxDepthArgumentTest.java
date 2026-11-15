package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code maxDepth} as clients send it (#762): a JSON number reaches a tool as an {@code Integer},
 * a {@code Long} or a {@code Double}, depending on its text and on the client's JSON library.
 */
class MaxDepthArgumentTest {

    private static final String CHAIN = """
            {"schemaVersion":1,"module":"m",
             "components":[
               {"qualifiedName":"x.A","scope":"SINGLETON","interfaces":[],
                "constructorDependencies":[{"type":"x.B","qualifier":null,"kind":"DIRECT","pickedType":null}]},
               {"qualifiedName":"x.B","scope":"SINGLETON","interfaces":[],
                "constructorDependencies":[{"type":"x.C","qualifier":null,"kind":"DIRECT","pickedType":null}]},
               {"qualifiedName":"x.C","scope":"SINGLETON","interfaces":[],"constructorDependencies":[]}
             ],
             "factoryMethods":[],"configurations":[],
             "eventHandlers":[
               {"declaringClass":"x.A","methodName":"h1","eventType":"x.E1","async":false,"hasEventWrapper":false},
               {"declaringClass":"x.B","methodName":"h2","eventType":"x.E2","async":false,"hasEventWrapper":false}
             ],
             "eventTriggers":[
               {"handlerClass":"x.A","handlerMethod":"h1","eventName":"E2","eventType":"x.E2","async":false,"spread":false,"guards":[]},
               {"handlerClass":"x.B","handlerMethod":"h2","eventName":"E3","eventType":"x.E3","async":false,"spread":false,"guards":[]}
             ]}
            """;

    private TopologyStore store;

    @BeforeEach
    void loadChain(@TempDir Path root) throws Exception {
        var file = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CHAIN, StandardCharsets.UTF_8);
        store = TopologyStore.loadFrom(root);
    }

    private int wiringNodes(Map<String, Object> args) {
        return ((List<?>) new ExplainWiringTool(store).execute(args).get("tree")).size();
    }

    private int flowNodes(Map<String, Object> args) {
        return ((List<?>) new TraceEventFlowTool(store).execute(args).get("nodes")).size();
    }

    static Stream<Arguments> wholeNumbers() {
        return Stream.of(Arguments.of("Integer", 1), Arguments.of("Long", 1L), Arguments.of("whole Double", 1.0d));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wholeNumbers")
    void maxDepthSentAsANumberLimitsTheWalk(String name, Object maxDepth) {
        assertThat(wiringNodes(Map.of("componentFqn", "x.A", "maxDepth", maxDepth)))
                .as("explain_wiring: x.A and x.B")
                .isEqualTo(2);
        assertThat(flowNodes(Map.of("eventType", "x.E1", "maxDepth", maxDepth)))
                .as("trace_event_flow: x.E1 and x.E2")
                .isEqualTo(2);
    }

    @Test
    void withoutMaxDepthTheWholeChainIsWalked() {
        assertThat(wiringNodes(Map.of("componentFqn", "x.A"))).isEqualTo(3);
        assertThat(flowNodes(Map.of("eventType", "x.E1"))).isEqualTo(3);
    }

    static Stream<Arguments> notADepth() {
        return Stream.of(Arguments.of("fraction", 1.5d), Arguments.of("negative", -1), Arguments.of("text", "deep"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notADepth")
    void maxDepthThatIsNoDepthIsRejectedByName(String name, Object maxDepth) {
        var wiring = new ExplainWiringTool(store);
        var flow = new TraceEventFlowTool(store);
        Map<String, Object> wiringArgs = Map.of("componentFqn", "x.A", "maxDepth", maxDepth);
        Map<String, Object> flowArgs = Map.of("eventType", "x.E1", "maxDepth", maxDepth);

        assertThatThrownBy(() -> wiring.execute(wiringArgs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
        assertThatThrownBy(() -> flow.execute(flowArgs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }
}
