# `tiko-mcp` design principle: per-partes answers, not bulk dumps

This is the rule every existing and future `tiko-mcp` tool must satisfy. It is a
constraint, not a guideline — new tools that violate it should be reshaped or
rejected before merge.

## The rule

**Each MCP tool answers one focused question with a small, bounded response.
The agent must be able to ask for *less*, never forced to fetch *all*.**

Corollaries:

1. **No tool returns "all of kind X" unconditionally.** If the agent might want a
   slice, the tool takes a filter / target FQN / qualifier. Filters are
   *required* unless the natural data volume is small (e.g. wiring errors,
   profile conflicts — both empty in healthy builds, never large).
2. **No tool embeds bulk data in a per-entry shape.** Enrichments that bloat
   every entry of a listing belong in a separate targeted tool, not as fields
   on the listing's entries.
3. **No tool returns file *contents* by default.** Return a path / pointer / tiny
   structured summary; the agent reads bytes via its filesystem tool if it
   actually needs them.
4. **Raw-JSON is the escape hatch, not the front door.** The full
   `META-INF/tiko/topology.json` is on disk and the agent can read it as a last
   resort. The MCP server should not provide a `dump_topology` tool that
   encourages that path; if the agent ends up there, the focused tools failed
   them and the gap is a backlog item.

## Why

Token prices make context economy a first-class concern. The original
assumption — that the agent reads the topology once into context and references
it thereafter — doesn't hold for any non-trivial codebase: a real topology
runs to thousands of components, hundreds of producers, and the per-session
cost of loading that into context dwarfs the value the agent extracts from
any single decision.

Per-partes serving inverts that: the agent pays only for the slice it asks
for, every turn. Over the lifetime of a session that adds up to a fraction of
the bulk-load cost.

## Audit of the current 9 tools (as of 2026-06-05)

| Tool                       | Question answered                              | Shape          | Verdict             |
| -------------------------- | ---------------------------------------------- | -------------- | ------------------- |
| `explain_wiring`           | "What does X depend on?"                       | targeted       | per-partes ✓        |
| `find_dependents`          | "What depends on X?"                           | targeted       | per-partes ✓        |
| `trace_event_flow`         | "What events does X trigger / chain into?"     | targeted       | per-partes ✓        |
| `reload`                   | "Reload topology from disk."                   | operation      | not a query — ok    |
| `list_wiring_errors`       | "What did the processor reject this build?"    | small by nature | low-volume ✓        |
| `list_profile_conflicts`   | "Where do profile-keyed beans overlap?"        | small by nature | low-volume ✓        |
| `get_config_schema`        | "What's the schema for prefix P?"              | filter optional | **borderline** — require `prefix` or have a "list prefixes" companion |
| `list_components`          | "What components exist matching filter?"       | filter optional | **needs reshape** — require at least one filter, OR return a count + first-N summary when called bare |
| `list_events`              | "What events exist matching filter?"           | filter optional | **needs reshape** — same |

The two `list_*` tools that accept-but-don't-require filters are the main
violators: an agent fresh to the codebase will reasonably call them with no
args and pay the full topology cost.

`get_config_schema` is borderline — without `prefix` it returns the whole
config schema, which can be sizeable on real projects. Lower priority than the
`list_*` reshape but worth tracking.

## What this means for in-flight Phase 6 work

- **`list_lifecycle_hooks` (#147)** — designed per-partes from the start
  (filter by phase). Ship as-is.
- **Proxy enrichment (#146)** — the original shape was "add `proxy: { interface,
  proxiedMethods, reason }` to `list_components` entries". That's corollary 2:
  bloating every entry of a listing. Reshape to a targeted tool
  `explain_proxy(componentFqn)` that answers "tell me about the proxy on this
  one bean" and returns just that bean's proxy info.
- **`get_generated_artifact` (#148)** — original shape was "return path *and
  contents* of generated source". Corollary 3: don't return contents. Return
  the path + tiny structured summary (kind: factory / proxy / container;
  scope; size in lines); the agent uses its filesystem read if it needs bytes.

## Adding a new tool

Before opening an issue for a new MCP tool, the proposal has to answer:

1. **What single question does this tool answer?** One sentence.
2. **What targets / filters does the tool accept?** (Not "what fields does it
   return.") Default-no-filter is suspicious.
3. **What's the worst-case response size on a real codebase?** If the honest
   answer is "could be large", the tool needs a reshape before it ships.

If the proposal is "expose X data the agent might find useful", that's the
wrong frame — start from the agent's question and work back.

## Sibling agent-facing surface

The MCP topology server is one of two agent-facing surfaces in tiko. The
other is the [tiko-build cookbook](./orchestrator-model.md) and its
[extension procedure](./cookbook-extension.md). Both follow the same
spirit: ask narrowly, don't dump; let the agent (and the user) reach for
the next question instead of drowning in everything-at-once. A tool that
violates the per-partes principle here belongs nowhere; a recipe written
without asking the user belongs in no cookbook.
