# `META-INF/tiko/topology.json` — schema v1

Every Tiko build emits a `topology.json` resource describing the wiring
discovered by the annotation processor. The file ships inside the jar so
downstream tools (and the [`tiko-mcp`](../tiko-mcp) server) can
introspect a project under development **or** an installed dependency.

To suppress emission for a module, add
`-Atiko.topology.bundle=false` to the annotation processor args.

## Stability

**v1 is additive-only.** New fields are optional. Renames or removals
require a major bump (`schemaVersion: 2`) plus a migration note. The
processor enforces this with a guard test that fails if anyone bumps
the constant without updating this document.

## Top-level shape

```json
{
  "schemaVersion": 1,
  "module": "io.tiko.generated.TikoContainerImpl_<hash>",
  "components": [ ... ],
  "factoryMethods": [ ... ],
  "eventHandlers": [ ... ],
  "eventTriggers": [ ... ],
  "configurations": [ ... ]
}
```

## `components[]`

Every `@Component` (including `@TestComponent`) collected in the
compile round.

| Field                     | Type            | Notes |
| ------------------------- | --------------- | ----- |
| `qualifiedName`           | string          | FQN of the impl class |
| `packageName`             | string          | |
| `simpleName`              | string          | |
| `scope`                   | string enum     | `SINGLETON` / `REQUEST` / `EVENT` / `PROTOTYPE` |
| `qualifier`               | string \| null  | `@Component(name = "...")`, null when unset |
| `profiles`                | string[]        | `@Component(profiles = ...)` |
| `interfaces`              | string[]        | FQNs of every directly-declared interface |
| `isTestComponent`         | boolean         | True when discovered via `@TestComponent` |
| `requiresProxy`           | boolean         | True when a cross-scope proxy was generated for this component |
| `exposeSelf`              | boolean         | `@Component(exposeSelf = ...)`, default true |
| `exposeTypes`             | string[]        | `@Component(expose = {...})`, empty = permissive default |
| `constructorDependencies` | object[]        | See below |
| `lifecycle`               | object          | See below |

### `constructorDependencies[]`

| Field         | Type            | Notes |
| ------------- | --------------- | ----- |
| `type`        | string          | Declared parameter type FQN (unwrapped from `Provider<T>` / `Picker<T>`) |
| `qualifier`   | string \| null  | `@Named(...)` value, null when unset |
| `kind`        | string enum     | `DIRECT` / `PROVIDER` / `PICKER` |
| `pickedType`  | string \| null  | `@Pick(SomeImpl.class)` target, null when unset |

### `lifecycle`

| Field           | Type     | Notes |
| --------------- | -------- | ----- |
| `postConstruct` | string[] | Method names |
| `preDestroy`    | string[] | Method names |
| `autoCloseable` | boolean  | True when implicit `close()` runs at scope teardown (no explicit `@PreDestroy`) |

## `factoryMethods[]`

Every `@Produces` method.

| Field             | Type           | Notes |
| ----------------- | -------------- | ----- |
| `declaringClass`  | string         | FQN of the `@Component` enclosing the method |
| `methodName`      | string         | |
| `returnType`      | string         | FQN of the returned type |
| `scope`           | string enum    | |
| `qualifier`       | string \| null | `@Produces(name = "...")`, null when unset |
| `profiles`        | string[]       | |
| `static`          | boolean        | True for static `@Produces` |
| `autoCloseable`   | boolean        | True when the produced type implements `AutoCloseable` |
| `requiresProxy`   | boolean        | True when a cross-scope proxy was generated |
| `constructorDependencies` | object[] | Same shape as `components[].constructorDependencies` (method parameter list) |

## `eventHandlers[]`

Every `@EventHandler` method.

| Field            | Type    | Notes |
| ---------------- | ------- | ----- |
| `declaringClass` | string  | |
| `methodName`     | string  | |
| `eventType`      | string  | FQN of the first parameter type |
| `async`          | boolean | `@EventHandler(async = ...)` |
| `hasEventWrapper`| boolean | True when the method takes a second `Event<?>` parameter |

## `eventTriggers[]`

Every `@EventTrigger` annotation (including each entry of an
`@EventTriggers` container).

| Field          | Type     | Notes |
| -------------- | -------- | ----- |
| `handlerClass` | string   | FQN of the `@EventHandler` carrying the trigger |
| `handlerMethod`| string   | |
| `eventName`    | string   | `@EventTrigger(eventName = ...)` |
| `async`        | boolean  | |
| `spread`       | boolean  | |
| `guards`       | string[] | FQNs of `EventTriggerGuard` classes; default `AlwaysAllow` is omitted |

**Caveat:** Only declarative `@EventTrigger` chains are captured.
Programmatic `EventBus.publish(...)` calls are invisible to the
processor and not listed here.

## `configurations[]`

Every `@Configuration` record.

| Field            | Type     | Notes |
| ---------------- | -------- | ----- |
| `qualifiedName`  | string   | |
| `prefix`         | string   | `@Configuration(prefix = ...)` |
| `fields[].name`  | string   | Record component name |
| `fields[].yamlKey` | string | `@Key("...")` override or `fields[].name` |
| `fields[].type`  | string   | TypeMirror string (e.g. `java.lang.String`, `java.util.List<java.lang.String>`) |
| `fields[].cardinality` | string enum | `REQUIRED` / `OPTIONAL` / `DEFAULTED` |
| `fields[].default` | string \| null | Raw `@Default("...")` value, null unless `DEFAULTED` |

## Consuming the file

From the shell:

```bash
find . -path '**/target/classes/META-INF/tiko/topology.json' -exec jq '.components[] | {name: .qualifiedName, scope}' {} +
```

From Python:

```python
import json, glob
for path in glob.glob('**/target/classes/META-INF/tiko/topology.json', recursive=True):
    with open(path) as f:
        topo = json.load(f)
    for c in topo['components']:
        print(c['qualifiedName'], c['scope'])
```

From an MCP-aware coding agent: see [`tiko-mcp`](../tiko-mcp).
