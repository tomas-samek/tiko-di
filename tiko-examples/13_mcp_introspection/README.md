# 13 — MCP introspection

A tiny Tiko app showing how an MCP-aware coding agent (Claude Code,
Cursor, …) can introspect the app's wiring at compile time.

## Components

| Class | Scope | Notes |
|---|---|---|
| `OrderService` | SINGLETON | handles `OrderPlaced` with `@EventHandler` + `@EventTrigger` |
| `OrderRepository` | REQUEST | proxy-injected into `OrderService` via `Orders` interface |
| `DbConfig` | — | `@Configuration(prefix = "database")` record |

## Setup

1. From the repository root, build everything:

       mvn -pl tiko-mcp,tiko-examples/13_mcp_introspection -am clean install

   This emits `META-INF/tiko/topology.json` + `config-schema.json` into
   `tiko-examples/13_mcp_introspection/target/classes/META-INF/tiko/` and
   produces the runnable `tiko-mcp/target/tiko-mcp-0.1.0.jar`.

2. Open this directory in your MCP-aware agent. The agent picks up
   `.mcp.json` automatically (Claude Code, Cursor) — or import it manually.

## Sample agent queries

### "List every singleton component"

Tool: `list_components`
Args: `{"scope": "SINGLETON"}`

Response:

```json
{
  "components": [
    {
      "qualifiedName": "example.OrderService",
      "scope": "SINGLETON",
      "interfaces": [],
      "constructorDependencies": [
        {"type": "example.Orders", "qualifier": null, "kind": "DIRECT", "pickedType": null}
      ]
    }
  ]
}
```

### "What handlers listen to OrderPlaced?"

Tool: `list_events`
Args: `{"eventType": "example.events.OrderPlaced"}`

Response:

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

Tool: `get_config_schema`
Args: `{}`

Response is a JSON Schema describing:
- `database.url` — required string
- `database.username` — required string
- `database.poolSize` — integer, default `10`

### "What does OrderService depend on?"

Tool: `explain_wiring`
Args: `{"componentFqn": "example.OrderService"}`

Response (depth-tagged tree):

```
example.OrderService [COMPONENT, SINGLETON, depth=0]
  example.OrderRepository [COMPONENT, REQUEST, depth=1]  ← resolved via Orders interface; proxied
    example.DbConfig [CONFIG, depth=2]                   ← @Configuration record, leaf
```

Each tree entry carries a `kind` field: `COMPONENT` for regular `@Component`
beans, `CONFIG` for `@Configuration` records (which are leaves — their fields
live in `get_config_schema`). The `via` field on each non-root entry records
the dependency edge that pulled it in, so interface-typed deps remain
traceable to the declared parameter type.
