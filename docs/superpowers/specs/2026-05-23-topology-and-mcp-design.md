# Machine-readable topology + config schema + MCP server

**Status:** Design approved 2026-05-23. Implementation plan to follow.
**Tracker:** [#22](https://github.com/tomas-samek/tiko-di/issues/22)
**Milestone:** Phase 3 — Onboarding & tooling
**Predecessors:**
- [#15](https://github.com/tomas-samek/tiko-di/issues/15) — `@Configuration` v1 (the records the config schema describes)
- [#20](https://github.com/tomas-samek/tiko-di/issues/20), [#21](https://github.com/tomas-samek/tiko-di/issues/21) — the archetype + AI-context layer this slots beside

## Goal

Make the Tiko-DI build emit two machine-readable artifacts and ship a
stdio MCP server so AI agents (Claude Code, Cursor, Junie, Copilot) and
IDE tooling can introspect the wiring of a Tiko project without running
it. The compile-time topology is the source of truth — Tiko has no
runtime container to query by design, so the only place this
information exists is the processor.

After this ships, an agent or IDE that points at a Tiko project can
answer:

- "What scope is `OrderService`?"
- "Which handlers subscribe to `OrderPlaced`?"
- "What YAML keys does this app accept under the `database` prefix?"
- "What does `OrderService` transitively depend on?"

## Non-goals

- **Runtime introspection.** Tiko has no `ApplicationContext` to query
  at runtime; the framework deliberately resolves everything at compile
  time. The MCP server reads the build artifacts, not a live process.
- **Write tools.** The MCP server is a read-only inspector. No "rename
  this component", no "add a dependency".
- **JSON parsing dependency in the processor.** Existing manifests
  (`components.txt`, `configs.txt`, `container.properties`,
  `test-shadows.properties`) are hand-rolled. We follow the same
  pattern for the two new JSON files to keep `tiko-processor` zero-new-deps.
- **A new manifest format.** Both artifacts use plain JSON; the config
  schema is proper [JSON Schema draft 2020-12](https://json-schema.org/draft/2020-12)
  so IntelliJ's existing YAML autocomplete picks it up.
- **An IntelliJ plugin.** The IntelliJ wiring (point its YAML support
  at the generated schema file) is a one-line user setup, not a Tiko
  artifact.

## Design decisions

The non-obvious calls, with rationale:

1. **Emit to `META-INF/tiko/topology.json` and
   `META-INF/tiko/config-schema.json` — not `target/tiko/…`.** The
   issue body suggests `target/tiko/topology.json` (a build-only
   location), but shipping the artifacts inside the jar unlocks
   strictly more use cases:

   - **Inspect third-party Tiko deps.** `tiko-mcp` (or any tool) can
     answer *"what components does `com.acme:billing-adapter:2.1`
     expose? what config keys?"* by reading the dep's jar — no source,
     no boot, no build of the consumer. Natural extension of Tiko's
     "compile-time, mechanically verifiable" positioning.
   - **MCP against a deployed fat jar.** No `target/` next to it, but
     the topology still ships with every constituent jar.
   - **Single locate strategy.** The MCP server walks the same path
     under `target/classes/` (project under development) **and** under
     any installed jar — one code path, two delivery channels.

   Cost is small: a medium service is ~20-50 KB compressed in the
   jar; information disclosure is bounded (bytecode already reveals
   every type and signature). The precedent matches: `components.txt`,
   `configs.txt`, `container.properties` already ship in the jar
   because the runtime container reads them across modules. Topology
   is the human/agent-facing equivalent.
2. **Hand-rolled JSON writer in `tiko-processor`.** A 200-line
   `JsonWriter` helper covers the shapes we emit. Pulling in Jackson
   adds ~2 MB to the processor jar and a transitive that downstream
   builds inherit on the annotation processor path. Not worth it.
3. **Hand-rolled JSON reader in `tiko-mcp`.** Same reasoning — the
   MCP server only reads two well-known shapes. A stdlib-only reader
   stays under 300 lines.
4. **Config schema is proper JSON Schema draft 2020-12, not an
   ad-hoc shape.** The issue's acceptance criterion explicitly calls
   out IntelliJ YAML autocomplete, which uses JSON Schema. An ad-hoc
   shape would mean a custom IntelliJ plugin — non-goal.
5. **Topology JSON shape: `schemaVersion: 1`, additive-only thereafter.**
   New fields are optional; renames or removals require a major bump.
   This keeps downstream tools (MCP server, future doc generators) from
   breaking on every Tiko release.
6. **MCP server: official `io.modelcontextprotocol:mcp` Java SDK.**
   Handles JSON-RPC framing + stdio loop. We only implement tool
   handlers. Rolling our own MCP transport is a non-goal — we want
   conformance with the protocol, not a re-implementation.
7. **MCP runs against a project directory, not a "Tiko service".**
   `java -jar tiko-mcp.jar /path/to/project` walks
   `**/target/classes/META-INF/tiko/{topology,config-schema}.json`
   (multi-module aware). No daemon, no socket — stdio MCP per the spec.
8. **Three PRs, in order: topology → config-schema → MCP.** Each
   stands alone and ships value (topology JSON is useful even without
   the MCP server; the MCP server only needs the two JSONs to exist).
   Keeps reviews focused.

## Architecture

### New files

```
tiko-processor/src/main/java/io/tiko/processor/topology/
├── TopologyWriter.java          ← META-INF/tiko/topology.json
├── ConfigSchemaWriter.java      ← META-INF/tiko/config-schema.json
└── JsonWriter.java              ← shared hand-rolled writer

tiko-mcp/
├── pom.xml
└── src/main/java/io/tiko/mcp/
    ├── TikoMcpServer.java       ← main(); registers tools with the SDK
    ├── TopologyStore.java       ← loads, caches, multi-module merges
    ├── JsonReader.java          ← stdlib JSON reader
    └── tools/
        ├── ListComponentsTool.java
        ├── ListEventsTool.java
        ├── GetConfigSchemaTool.java
        └── ExplainWiringTool.java

tiko-examples/13_mcp_introspection/
├── pom.xml
├── src/main/java/example/...    ← a small Tiko app with config + events
├── .mcp.json                    ← Claude Code MCP wiring snippet
└── README.md                    ← transcript of agent queries

docs/topology-schema.md          ← v1 schema reference + additive rule
```

### Modified files

```
tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
  — call TopologyWriter and ConfigSchemaWriter in generate()
    (after ConfigManifestWriter; gated on inputs being non-empty)

pom.xml (root)
  — add <module>tiko-mcp</module>

tiko-bom/pom.xml
  — pin io.modelcontextprotocol:mcp version

README.md
  — one-line install snippet for the MCP server
  — add tiko-mcp + topology.json bullets to "What ships today"

docs/roadmap.md
  — mark #22 closed in Phase 3
```

### Data flow

```
mvn compile
  └─ tiko-processor (final round)
       └─ generate()
            ├─ existing: factories, proxies, EventRegistry, container, ConfigBinders
            ├─ existing: ConfigManifestWriter      → META-INF/tiko/configs.txt
            ├─ NEW:      TopologyWriter            → META-INF/tiko/topology.json
            └─ NEW:      ConfigSchemaWriter        → META-INF/tiko/config-schema.json

tiko-mcp.jar (runtime)
  └─ TikoMcpServer.main(args[0] = projectDir)
       └─ TopologyStore.loadFrom(projectDir)
            └─ glob: **/target/classes/META-INF/tiko/{topology,config-schema}.json
                 └─ JsonReader → in-memory model
                      └─ register MCP tools → stdio loop
```

## Topology JSON v1 — full schema

```json
{
  "schemaVersion": 1,
  "module": "io.tiko.generated.TikoContainerImpl_a1b2c3",
  "components": [
    {
      "qualifiedName": "com.acme.OrderService",
      "packageName": "com.acme",
      "simpleName": "OrderService",
      "scope": "SINGLETON",
      "qualifier": null,
      "profiles": [],
      "interfaces": ["com.acme.api.Orders"],
      "isTestComponent": false,
      "requiresProxy": false,
      "exposeSelf": true,
      "exposeTypes": [],
      "constructorDependencies": [
        {
          "type": "com.acme.OrderRepository",
          "qualifier": null,
          "kind": "DIRECT",
          "pickedType": null
        }
      ],
      "lifecycle": {
        "postConstruct": ["init"],
        "preDestroy": [],
        "autoCloseable": false
      }
    }
  ],
  "factoryMethods": [
    {
      "declaringClass": "com.acme.DataSources",
      "methodName": "mysql",
      "returnType": "javax.sql.DataSource",
      "scope": "SINGLETON",
      "qualifier": "mysql",
      "profiles": [],
      "static": false,
      "autoCloseable": true,
      "requiresProxy": false,
      "dependencies": [
        {"type": "com.acme.DbConfig", "qualifier": null, "kind": "DIRECT", "pickedType": null}
      ]
    }
  ],
  "eventHandlers": [
    {
      "declaringClass": "com.acme.NotificationService",
      "methodName": "onOrderPlaced",
      "eventType": "com.acme.OrderPlaced",
      "async": false,
      "hasEventWrapper": false
    }
  ],
  "eventTriggers": [
    {
      "handlerClass": "com.acme.OrderService",
      "handlerMethod": "validate",
      "eventName": "OrderValidated",
      "async": false,
      "spread": false,
      "guards": []
    }
  ],
  "configurations": [
    {
      "qualifiedName": "com.acme.DbConfig",
      "prefix": "database",
      "fields": [
        {
          "name": "url",
          "yamlKey": "url",
          "type": "java.lang.String",
          "cardinality": "REQUIRED",
          "default": null
        },
        {
          "name": "poolSize",
          "yamlKey": "poolSize",
          "type": "int",
          "cardinality": "DEFAULTED",
          "default": "10"
        }
      ]
    }
  ]
}
```

**Field notes:**
- `qualifier`: the `@Component(name="…")` value, or null. Same shape
  for `@Named`-disambiguated factories.
- `interfaces`: every interface the impl declares (matches the routing
  surface, modulo `exposeSelf`/`exposeTypes`).
- `kind` on a dependency: `DIRECT` | `PROVIDER` | `PICKER` (mirrors
  `DependencyModel.isProvider()` / `isPicker()`).
- `pickedType`: non-null only when `@Pick(SomeImpl.class)` is used at
  the injection point; carries the picked FQN.
- `lifecycle.autoCloseable`: true when implicit `close()` runs at
  scope teardown (no explicit `@PreDestroy`).
- `requiresProxy`: true when the framework generated a cross-scope
  proxy for this component or factory output.

**Field additions later:** any field added in a future Tiko release is
optional. Renames and removals require `schemaVersion: 2`.

## Config Schema JSON shape

Proper JSON Schema draft 2020-12. One file per module unioning every
`@Configuration` record under its prefix as a top-level property.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "tiko://config-schema",
  "type": "object",
  "title": "Tiko @Configuration union",
  "properties": {
    "database": {
      "type": "object",
      "title": "com.acme.DbConfig",
      "properties": {
        "url":      {"type": "string"},
        "username": {"type": "string"},
        "poolSize": {"type": "integer", "default": 10},
        "connectTimeout": {"type": "string", "format": "duration", "default": "PT30S"}
      },
      "required": ["url", "username"],
      "additionalProperties": false
    },
    "cache": {
      "type": "object",
      "title": "com.acme.CacheConfig",
      "properties": {
        "maxSize": {"type": "integer", "default": 10000}
      },
      "additionalProperties": false
    }
  },
  "additionalProperties": true
}
```

**Type mapping** (delegates to a new `JsonSchemaTypeMapper` that
reuses `ConfigSupportedTypes`):

| Java type                | JSON Schema                      |
| ------------------------ | -------------------------------- |
| `String`                 | `{"type": "string"}`             |
| `int`, `long`, `short`   | `{"type": "integer"}`            |
| `double`, `float`        | `{"type": "number"}`             |
| `boolean`                | `{"type": "boolean"}`            |
| enum                     | `{"type": "string", "enum": [...]}` |
| `Duration`               | `{"type": "string", "format": "duration"}` |
| `Instant`                | `{"type": "string", "format": "date-time"}` |
| `URI` / `URL`            | `{"type": "string", "format": "uri"}` |
| `List<T>` / `Set<T>`     | `{"type": "array", "items": <T>}` |
| `Map<String,T>`          | `{"type": "object", "additionalProperties": <T>}` |
| nested record            | nested `object` with its own `properties`/`required` |

**Required vs optional:** `Cardinality.REQUIRED` → listed in `required`.
`OPTIONAL` and `DEFAULTED` are not listed. `DEFAULTED` carries the
`default` keyword in JSON Schema (verbatim from `@Default("…")`).

**Top-level `additionalProperties: true`** because the YAML root may
carry framework-reserved keys (`tiko.shutdownTimeout`, etc.) the
schema doesn't enumerate. Per-prefix sub-objects use
`additionalProperties: false` for tight validation.

## MCP server — `tiko-mcp`

### Module layout

```xml
<artifactId>tiko-mcp</artifactId>

<dependencies>
  <dependency>
    <groupId>io.modelcontextprotocol</groupId>
    <artifactId>mcp</artifactId>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <artifactId>maven-shade-plugin</artifactId>
      <configuration>
        <createDependencyReducedPom>false</createDependencyReducedPom>
        <transformers>
          <transformer implementation="...ManifestResourceTransformer">
            <mainClass>io.tiko.mcp.TikoMcpServer</mainClass>
          </transformer>
        </transformers>
      </configuration>
      <executions>
        <execution>
          <phase>package</phase>
          <goals><goal>shade</goal></goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Produces a runnable `tiko-mcp-<version>.jar` with the MCP SDK shaded
in. Users wire it into `.mcp.json` / Cursor / `.claude.json`:

```json
{
  "mcpServers": {
    "tiko": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/tiko-mcp.jar", "/abs/path/to/project"]
    }
  }
}
```

### Project loader (`TopologyStore`)

On startup:
1. Walk `<projectDir>/**/target/classes/META-INF/tiko/topology.json`
   (recursive — picks up every module in a multi-module Maven build).
2. Walk `<projectDir>/**/target/classes/META-INF/tiko/config-schema.json`.
3. Parse each with `JsonReader`, build an in-memory union model.
4. On any module's topology change (mtime check on next tool call),
   reload that module's slice. Cheap; tool calls are interactive.

If `<projectDir>/target/classes/...` doesn't exist yet, return a tool
error: *"Run `mvn compile` in the project first — Tiko's topology
artifacts are emitted at compile time."*

### Tool surface

All four tools return JSON. Argument validation is enforced via the
SDK's JSON-schema-typed input declarations.

#### `list_components`

```
inputs:  { "scope"?: "SINGLETON"|"REQUEST"|"EVENT"|"PROTOTYPE",
           "interface"?: string }
returns: { "components": [ <topology component objects, filtered> ] }
```

No filter → returns every component. `scope` filters by `Scope`
enum value. `interface` filters to components whose `interfaces[]`
includes the given FQN (exact match).

#### `list_events`

```
inputs:  { "eventType"?: string }
returns: { "events": [
            { "eventType": string,
              "publishers": [ { "class": string, "method": string,
                                "eventName": string, "async": boolean } ],
              "handlers":   [ { "class": string, "method": string, "async": boolean } ]
            }
         ] }
```

Cross-references `eventHandlers[]` (subscribers) with `eventTriggers[]`
(publishers via `@EventTrigger`). `eventType` filter is exact-match
on the FQN.

**Publisher caveat:** `eventTriggers[]` only captures declarative
`@EventTrigger` chains. Programmatic `EventBus.publish(...)` calls
are invisible to the processor and not listed.

#### `get_config_schema`

```
inputs:  { "prefix"?: string }
returns: { "schema": <full JSON Schema or properties.<prefix> slice> }
```

No `prefix` → returns the full union schema. With `prefix` → returns
just `properties.<prefix>`. Unknown prefix → tool error listing the
known prefixes.

#### `explain_wiring`

```
inputs:  { "componentFqn": string,
           "maxDepth"?: integer (default 10) }
returns: { "root": <component>,
           "tree": [
             { "depth": int, "component": <component>,
               "via": <dependency that pulled it in>,
               "cycle": boolean,
               "proxied": boolean }
           ] }
```

BFS over `constructorDependencies` resolving each `type` (and
`qualifier`) back to a topology component. Stops at `maxDepth`.
Marks cycles as `cycle: true` (does not throw — Tiko already rejects
real cycles at compile time, but the BFS visits the same node twice
via separate paths and we want to flag re-visits to keep the response
finite). Marks edges where `requiresProxy: true` so agents can
explain auto-proxies.

Unknown `componentFqn` → tool error listing close FQN matches
(simple substring filter on `components[].qualifiedName`).

### Logging

`tiko-mcp` follows the same `System.Logger` convention as the rest of
the framework. JUL is the default route; the server logs to
`stderr` so the JSON-RPC stdio channel stays clean.

## Example — `tiko-examples/13_mcp_introspection`

A single-module Tiko app with:
- one SINGLETON service, one REQUEST-scoped repository
- one `@Configuration` record (so the config schema has content)
- one `@EventHandler` + one `@EventTrigger` chain (so events show up)

Files:
```
tiko-examples/13_mcp_introspection/
├── pom.xml
├── README.md                    ← install snippet + sample queries
├── .mcp.json                    ← Claude Code MCP wiring
├── config.yaml
└── src/main/java/example/
    ├── Main.java
    ├── OrderService.java
    ├── OrderRepository.java
    ├── DbConfig.java
    └── events/{OrderPlaced,OrderValidated}.java
```

README transcript demonstrates four agent queries — one per MCP tool
— with the actual JSON the server returns.

## Documentation

### `docs/topology-schema.md` (new)

- v1 schema reference (one section per top-level field)
- the additive-only rule
- "future fields will be optional" guarantee
- example of consuming the file from a custom tool (10-line Python or Bash glob)

### `README.md` updates

Two surgical additions:

1. Under "Phase 3 — onboarding & tooling" bullet list (in the
   "What ships today" section), append a row for #22 once shipped.
2. New short section after the Maven archetype section:

   ```markdown
   ### AI-agent topology server (MCP)

   Every Tiko build emits machine-readable topology + config schema to
   `META-INF/tiko/`. The `tiko-mcp` companion jar exposes them to any
   MCP-aware coding agent (Claude Code, Cursor, …):

       java -jar tiko-mcp.jar /path/to/your/project

   See [`tiko-examples/13_mcp_introspection`](./tiko-examples/13_mcp_introspection)
   for a runnable demo.
   ```

### `docs/roadmap.md` updates

Move #22 from "Open" → "Shipped" in Phase 3 once landed; close the
Phase 3 milestone in the roadmap intro (6/6 closed).

## Testing strategy

### PR 1 — Topology JSON

- **Unit tests on `JsonWriter`**: round-trip every JSON primitive,
  escaping, nesting, array handling, empty-object handling. AssertJ on
  string output.
- **Processor IT (`compile-testing`)**: compile a fixture with one
  `@Component`, one `@Produces`, one `@EventHandler`, one
  `@EventTrigger`, one `@Configuration`. Read the generated
  `META-INF/tiko/topology.json` from the in-memory file manager and
  assert key fields with a hand-rolled JSON-walker. Cover:
  - scope, qualifier, profiles
  - interfaces (one + multiple)
  - dependencies (DIRECT, PROVIDER, PICKER)
  - test components in a separate fixture
  - empty inputs → file not emitted
- **Schema-versioning guard test**: a literal-string assertion that
  the top-level `"schemaVersion": 1` field is present. If anyone bumps
  this number, the test fails and forces them to read the additive
  rule.

### PR 2 — Config Schema JSON

- **Unit tests on `JsonSchemaTypeMapper`**: one row per type in the
  mapping table.
- **Processor IT**: compile a fixture with a `@Configuration` record
  exercising required, optional, defaulted, enum, `List<String>`,
  `Set<String>`, `Map<String,String>`, nested record. Read the
  generated `META-INF/tiko/config-schema.json` and assert the JSON
  Schema is valid (using `org.everit.json.schema` or
  `com.networknt:json-schema-validator` — already permissive licenses).
- **End-to-end smoke**: in an integration test, register the generated
  schema with an in-memory YAML-to-JSON validator and confirm a valid
  YAML passes and an invalid YAML (missing required) fails.

### PR 3 — `tiko-mcp`

- **`TopologyStore` unit tests**: feed it a fixture directory with two
  modules' topology JSONs and assert the merged in-memory model.
- **Tool handler tests**: invoke each of the four tools with the SDK's
  in-process test transport (no real stdio) and assert the JSON
  responses. Cover happy paths + filter cases + missing-input errors.
- **`explain_wiring` cycle test**: synthetic topology with a cycle;
  assert `cycle: true` on the re-visit.
- **End-to-end smoke**: spawn the shaded jar as a subprocess against
  `tiko-examples/13_mcp_introspection`, write a JSON-RPC `tools/list`
  request to stdin, parse the response from stdout, assert the four
  tools are advertised. (Uses `ProcessBuilder`; tolerant of slow CI.)

## Acceptance (matches issue #22)

- [ ] `META-INF/tiko/topology.json` emitted on every compile when
  the source set has at least one `@Component`, `@Produces`,
  `@EventHandler`, or `@Configuration`. v1 schema documented in
  `docs/topology-schema.md`.
- [ ] `META-INF/tiko/config-schema.json` emitted when at least one
  `@Configuration` record is present. Valid JSON Schema draft 2020-12.
- [ ] `tiko-mcp` runnable jar passes MCP protocol smoke test
  (`tools/list` returns the four tools; each tool call returns valid
  JSON).
- [ ] `tiko-examples/13_mcp_introspection` runs end-to-end: `mvn
  compile` emits both JSONs; `tiko-mcp` answers all four tool calls;
  README shows the transcript.
- [ ] README has a one-line install snippet under a short "AI-agent
  topology server" section.
- [ ] Roadmap reflects #22 as shipped, Phase 3 milestone closed.

## Out of scope

- Runtime introspection endpoints / live MCP server connected to a
  running app.
- Write tools on the MCP server. Read-only inspector only.
- IntelliJ / VSCode plugins. Users wire the JSON Schema into their
  editor manually (one-line YAML mapping).
- Pretty-printing tooling for `topology.json`. The artifact is for
  tools, not humans; if a human wants to read it they pipe through
  `jq`.
- Programmatic event publishers (`bus.publish(...)`) in `list_events`.
  The processor can't see them; the tool documents this.

## PR decomposition

Three PRs, each independently mergeable and reviewable:

1. **PR 1 — Topology JSON.** `TopologyWriter`, `JsonWriter`,
   `docs/topology-schema.md`, processor wiring, IT, README bullet.
2. **PR 2 — Config Schema JSON.** `ConfigSchemaWriter`,
   `JsonSchemaTypeMapper`, processor wiring, IT.
3. **PR 3 — `tiko-mcp` module + example.** New `tiko-mcp/` module,
   shaded jar, four tools, `tiko-examples/13_mcp_introspection/`,
   README section.

Land in order. Each PR closes a sub-deliverable of #22; the final PR
closes the issue.

## References

- `tiko-processor/src/main/java/io/tiko/processor/config/ConfigManifestWriter.java`
  — pattern for emitting `META-INF/tiko/<file>`
- `tiko-processor/src/main/java/io/tiko/processor/model/ComponentModel.java`
  — source-of-truth for the component fields in topology.json
- `tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationModel.java`,
  `ConfigFieldModel.java` — source-of-truth for config schema fields
- `tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java`
  — type-mapping reference for `JsonSchemaTypeMapper`
- [JSON Schema draft 2020-12](https://json-schema.org/draft/2020-12)
- [Model Context Protocol spec](https://modelcontextprotocol.io)
- [`io.modelcontextprotocol:mcp` Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [#22](https://github.com/tomas-samek/tiko-di/issues/22) — tracker
