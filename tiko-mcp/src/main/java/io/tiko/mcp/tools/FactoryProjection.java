package io.tiko.mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper that projects a raw {@code factoryMethods[]} entry (from
 * {@code topology.json}) into the synthetic component representation used by
 * {@link ListComponentsTool} and {@link ExplainWiringTool}.
 */
final class FactoryProjection {

    private FactoryProjection() {}

    /**
     * Projects a single factory-method entry into the {@code PRODUCED} component
     * shape expected by all MCP tools.
     *
     * <p>The projected map contains:
     * <ul>
     *   <li>{@code kind} — always {@code "PRODUCED"}</li>
     *   <li>{@code qualifiedName} — the factory method's {@code returnType}</li>
     *   <li>{@code scope}, {@code qualifier}, {@code profiles}</li>
     *   <li>{@code interfaces} — empty list (not known from producer signature)</li>
     *   <li>{@code isTestComponent} — always {@code false}</li>
     *   <li>{@code requiresProxy}, {@code constructorDependencies}</li>
     *   <li>{@code producedBy} — sub-map with {@code componentFqn},
     *       {@code methodName}, {@code isStatic}</li>
     * </ul>
     */
    static Map<String, Object> fromFactory(Map<String, Object> f) {
        var out = new LinkedHashMap<String, Object>();
        out.put("kind", "PRODUCED");
        out.put("qualifiedName", f.get("returnType"));
        out.put("scope", f.get("scope"));
        out.put("qualifier", f.get("qualifier"));
        out.put("profiles", f.getOrDefault("profiles", List.of()));
        out.put("interfaces", List.of());
        out.put("isTestComponent", false);
        out.put("requiresProxy", f.getOrDefault("requiresProxy", false));
        out.put("constructorDependencies", f.getOrDefault("constructorDependencies", List.of()));
        var producedBy = new LinkedHashMap<String, Object>();
        producedBy.put("componentFqn", f.get("declaringClass"));
        producedBy.put("methodName", f.get("methodName"));
        producedBy.put("isStatic", f.getOrDefault("static", false));
        out.put("producedBy", producedBy);
        return out;
    }
}
