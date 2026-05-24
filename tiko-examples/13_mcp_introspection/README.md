# 13 — MCP introspection

A tiny Tiko app showing how an MCP-aware coding agent (Claude Code,
Cursor, …) can introspect the app's wiring at compile time. After the
#140-#145 batch, the MCP server advertises **nine** read-only tools.

## Components

| Class | Scope | Notes |
|---|---|---|
| `OrderService` | SINGLETON | handles `OrderPlaced` with `@EventHandler` + `@EventTrigger("OrderValidated")` |
| `OrderRepository` | REQUEST | proxy-injected into `OrderService` via the `Orders` interface |
| `Orders` | — | injection-point interface implemented by `OrderRepository` |
| `DbConfig` | — | `@Configuration(prefix = "database")` record |
| `DbConfig.HikariShim` | — | nested record returned by the `@Produces` factory below |
| `Producers` | SINGLETON | hosts `@Produces(name = "primary") HikariShim primaryShim(DbConfig)` |
| `IGreeter` | — | profile-pinned interface (no impl active under the default profile) |
| `DevGreeter` | SINGLETON | `profiles = {"dev"}`, implements `IGreeter` |
| `ProdGreeter` | SINGLETON | `profiles = {"prod"}`, implements `IGreeter` |

Plus two events: `OrderPlaced` (input to `OrderService.validate`) and
`OrderValidated` (the trigger output published downstream).

## Setup

1. From the repository root, build everything:

       mvn -pl tiko-mcp,tiko-examples/13_mcp_introspection -am clean install

   This emits `META-INF/tiko/topology.json`, `config-schema.json`, and
   `wiring-errors.json` into
   `tiko-examples/13_mcp_introspection/target/classes/META-INF/tiko/`
   and produces the runnable `tiko-mcp/target/tiko-mcp-0.1.0.jar`.

2. Open this directory in your MCP-aware agent. The agent picks up
   `.mcp.json` automatically (Claude Code, Cursor) — or import it manually.

## The nine tools

| Tool | Issue | What it answers |
|---|---|---|
| `list_components` | original | every `@Component` + projected `@Produces` outputs as `kind: PRODUCED`; filters by `scope`, `interface`, `profile` |
| `list_events` | original | event handlers and (when known) publishers per event type |
| `get_config_schema` | original | merged JSON Schema over every `@Configuration` |
| `explain_wiring` | original | BFS tree of a component's constructor-dep graph; follows producer edges; honours `profile` |
| `reload` | #145 | re-reads `META-INF/tiko/*.json` from disk after `mvn compile`, no server restart |
| `list_wiring_errors` | #142 | structured processor diagnostics persisted to `wiring-errors.json` |
| `find_dependents` | #141, #183 | reverse-index lookup over both constructor and `@Produces` deps; optional `transitive: true` walks the graph |
| `trace_event_flow` | #140 | static DAG over `@EventTrigger` chains from a given event type |
| `list_profile_conflicts` | #144 | (interface, qualifier) groups whose entries pin to ≥2 distinct profiles |

## Sample agent queries

### "List every singleton component"

Tool: `list_components` &nbsp; Args: `{"scope": "SINGLETON"}`

Returns `OrderService`, `Producers`, `DevGreeter`, `ProdGreeter`, plus a
`kind: "PRODUCED"` synthetic entry for `DbConfig.HikariShim` (the @Produces
output, with `producedBy: {componentFqn: "example.Producers", methodName: "primaryShim", isStatic: false}`).

### "What handlers listen to OrderPlaced?"

Tool: `list_events` &nbsp; Args: `{"eventType": "example.events.OrderPlaced"}`

```json
{
  "events": [
    {
      "eventType": "example.events.OrderPlaced",
      "publishers": [],
      "handlers": [
        {"class": "example.OrderService", "method": "validate", "async": false}
      ]
    }
  ]
}
```

### "What config keys does this app accept?"

Tool: `get_config_schema` &nbsp; Args: `{}`

Returns a JSON Schema describing:
- `database.url` — required string
- `database.username` — required string
- `database.poolSize` — integer, default `10`

### "What does OrderService depend on?"

Tool: `explain_wiring` &nbsp; Args: `{"componentFqn": "example.OrderService"}`

```
example.OrderService [COMPONENT, SINGLETON, depth=0]
  example.OrderRepository [COMPONENT, REQUEST, depth=1]  ← resolved via Orders interface; proxied
    example.DbConfig [CONFIG, depth=2]                   ← @Configuration record, leaf
```

Each tree entry carries `kind` (`COMPONENT` / `PRODUCED` / `CONFIG`),
`depth`, `via` (the dep edge that pulled it in — preserves interface-typed
parameter types), and `proxied`. `CONFIG` is a leaf because its fields live
in `get_config_schema`.

### "Refresh after a rebuild" (#145)

Tool: `reload` &nbsp; Args: `{}`

After editing source and running `mvn compile`, call `reload` to refresh
the in-memory store without restarting the server:

```json
{"reloaded": true, "topologyTimestamp": "2026-05-24T11:30:42.123Z"}
```

### "Show any compile-time wiring errors" (#142)

Tool: `list_wiring_errors` &nbsp; Args: `{}`

On a clean build:

```json
{"errors": []}
```

If the processor recorded a diagnostic (missing dep, ambiguous qualifier,
scope violation, bad `@Produces`, circular dep), each entry carries
`kind`, `componentFqn`, `message`, optional `suggestedFix`, and a
best-effort `sourceFile` / `line`.

### "Who depends on DbConfig?" (#141, #183)

Tool: `find_dependents` &nbsp; Args: `{"componentFqn": "example.DbConfig"}`

```json
{"dependents": ["example.OrderRepository", "example.Producers"]}
```

`OrderRepository` injects `DbConfig` via constructor; `Producers` hosts the
`primaryShim` `@Produces` method that takes `DbConfig` as a parameter (#183
extended the walk to include factory-host components). When the same host
both injects AND produces using the target, it appears at most once. Pass
`"transitive": true` to walk the reverse graph (dependents-of-dependents).

### "Trace the event flow from OrderPlaced" (#140)

Tool: `trace_event_flow` &nbsp; Args: `{"eventType": "example.events.OrderPlaced"}`

```json
{
  "root": "example.events.OrderPlaced",
  "nodes": [
    {"event": "example.events.OrderPlaced", "depth": 0, "cycle": false, "terminal": false,
     "edges": [
       {"via": "example.OrderService#validate", "eventName": "OrderValidated",
        "async": false, "spread": false, "guards": [],
        "nextEvent": "example.events.OrderValidated"}
     ]},
    {"event": "example.events.OrderValidated", "depth": 1, "cycle": false, "terminal": true,
     "edges": []}
  ]
}
```

Pure static derivation — programmatic `EventBus.publish(...)` calls aren't
seen. Cycles emit a duplicate node with `cycle: true` and an empty `edges[]`.

### "Where do dev and prod implementations conflict?" (#144)

Tool: `list_profile_conflicts` &nbsp; Args: `{}`

```json
{
  "conflicts": [
    {
      "type": "example.profiles.IGreeter",
      "qualifier": null,
      "implementations": [
        {"qualifiedName": "example.profiles.DevGreeter",  "profiles": ["dev"]},
        {"qualifiedName": "example.profiles.ProdGreeter", "profiles": ["prod"]}
      ]
    }
  ]
}
```

A "default + dev override" pattern (one wildcard impl plus one dev-pinned)
is **not** flagged — only ≥2 distinct non-empty profile values count as a
conflict.

The same profile awareness flows into the other tools: pass
`{"profile": "prod"}` to `list_components` and `explain_wiring` and the
responses filter out impls whose profiles don't include `"prod"` (impls
with empty `profiles[]` are treated as wildcards and remain visible).
