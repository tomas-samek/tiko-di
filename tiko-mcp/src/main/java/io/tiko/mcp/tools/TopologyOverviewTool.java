package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MCP tool: a fixed-shape orientation snapshot for a fresh agent to call before
 * drilling into specific queries. Answers "where am I, what's the shape of this
 * codebase, what should I look at next?" in a single bounded response.
 *
 * <p>Per-partes-aligned by construction (see {@code docs/mcp-design.md}): the
 * response has a fixed set of fields, none of which scale with codebase size.
 *
 * <p>Multi-module reactors aggregate every module's topology into a single store,
 * which is why this tool does not surface a single {@code module} or
 * {@code schemaVersion} (those live per-module in the source files and would be
 * meaningless flattened). If the agent needs to know which modules are present,
 * that is a separate per-partes query — not bundled here.
 */
public final class TopologyOverviewTool {

    public static final String NAME = "topology_overview";

    private static final String QUERY_LIST_COMPONENTS = ListComponentsTool.NAME;
    private static final String QUERY_LIST_EVENTS = ListEventsTool.NAME;
    private static final String QUERY_LIST_LIFECYCLE_HOOKS = ListLifecycleHooksTool.NAME;
    private static final String QUERY_LIST_WIRING_ERRORS = ListWiringErrorsTool.NAME;
    private static final String QUERY_LIST_PROFILE_CONFLICTS = ListProfileConflictsTool.NAME;

    private final TopologyStore store;
    private final ListProfileConflictsTool profileConflicts;

    public TopologyOverviewTool(TopologyStore store) {
        this.store = store;
        this.profileConflicts = new ListProfileConflictsTool(store);
    }

    @SuppressWarnings("unused") // args param is required by the McpStdioBridge tool-registration contract
    public Map<String, Object> execute(Map<String, Object> args) {
        var out = new LinkedHashMap<String, Object>();
        out.put("loadedAt", store.loadedAt().toString());

        var wiringErrorsCount = store.wiringErrors().size();
        var totals = new LinkedHashMap<String, Object>();
        totals.put("components", store.components().size());
        totals.put("factoryMethods", store.factoryMethods().size());
        totals.put("eventHandlers", store.eventHandlers().size());
        totals.put("eventTriggers", store.eventTriggers().size());
        totals.put("kafkaSources", store.kafkaSources().size());
        totals.put("kafkaSinks", store.kafkaSinks().size());
        totals.put("configurations", store.configurations().size());
        totals.put("wiringErrors", wiringErrorsCount);
        out.put("totals", totals);

        out.put("scopes", aggregateScopes());

        boolean hasProfileConflicts = anyProfileConflicts();
        out.put("hasProfileConflicts", hasProfileConflicts);

        out.put("suggestedNextQueries", suggestedNextQueries(wiringErrorsCount, hasProfileConflicts));

        return out;
    }

    private Map<String, Object> aggregateScopes() {
        // Sorted output for stable test assertions; component-side and factory-side counted together.
        var counts = new TreeMap<String, Integer>();
        for (var c : store.components()) {
            bumpScope(counts, c);
        }
        for (var f : store.factoryMethods()) {
            bumpScope(counts, f);
        }
        return new LinkedHashMap<>(counts);
    }

    private static void bumpScope(Map<String, Integer> counts, Map<String, Object> entry) {
        var scope = entry.get("scope");
        if (scope == null) return;
        counts.merge(scope.toString(), 1, Integer::sum);
    }

    @SuppressWarnings("unchecked")
    private boolean anyProfileConflicts() {
        var conflictResult = profileConflicts.execute(Map.of());
        var conflicts = (List<Object>) conflictResult.getOrDefault("conflicts", List.of());
        return !conflicts.isEmpty();
    }

    /**
     * Three suggestions, ordered by what most likely needs attention first. The list is
     * intentionally short — the agent narrows from here, it doesn't read a menu.
     */
    private static List<String> suggestedNextQueries(int wiringErrorsCount, boolean hasProfileConflicts) {
        if (wiringErrorsCount > 0) {
            return List.of(QUERY_LIST_WIRING_ERRORS, QUERY_LIST_COMPONENTS, QUERY_LIST_LIFECYCLE_HOOKS);
        }
        if (hasProfileConflicts) {
            return List.of(QUERY_LIST_PROFILE_CONFLICTS, QUERY_LIST_COMPONENTS, QUERY_LIST_LIFECYCLE_HOOKS);
        }
        return List.of(QUERY_LIST_COMPONENTS, QUERY_LIST_EVENTS, QUERY_LIST_LIFECYCLE_HOOKS);
    }
}
