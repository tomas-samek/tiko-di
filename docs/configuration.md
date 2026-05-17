# Typed configuration

Tiko binds YAML directly to Java `record`s annotated with `@Configuration`. The annotation processor generates a per-record binder at compile time — no reflection at runtime, type errors are reported once at container startup, and the bound record is registered as a `SINGLETON` bean injectable like any other component.

For a runnable example, see [`tiko-examples/02_config`](../tiko-examples/02_config).

## Annotations

- **`@Configuration(prefix)`** — marks a record as a YAML-backed configuration root. `prefix` is the top-level YAML key under which this record's data is read.
- **`@Default(value)`** — supplies a default for a record component when the corresponding YAML key is absent. The default is parsed at compile time using the same coercer the runtime uses, so a malformed default fails the build.
- **`@Key(value)`** — overrides the field-name → YAML-key mapping (e.g. for kebab-case YAML keys).

## End-to-end example

```java
@Configuration(prefix = "db")
public record DbConfig(
        String url,
        @Default("10") int maxConnections,
        Optional<Duration> connectTimeout) {}

@Component(scope = Scope.SINGLETON)
public class UserRepository {
    @Inject
    public UserRepository(DbConfig config) {
        // config.url(), config.maxConnections(), config.connectTimeout()
    }
}
```

```yaml
db:
  url: ${DB_URL:jdbc:postgres://localhost/example}
  maxConnections: 20
  connectTimeout: PT5S
```

```java
Container container = Tiko.create(ConfigSources.classpath("config.yaml"));
```

## `${VAR}` interpolation

Values in YAML files support environment-variable interpolation:

- `${DB_URL}` — substitute the value of `$DB_URL`; fail if undefined.
- `${DB_URL:default-value}` — substitute `$DB_URL` if set, otherwise use `default-value`.

Interpolation is applied before type coercion.

## Module-baked defaults + a single external override

Each module can ship its own `META-INF/tiko/defaults.yaml` inside its jar. At startup, Tiko discovers every such file on the classpath, deep-merges them, and layers the user-supplied `ConfigSource` on top. Any single value in any module's defaults is overrideable per key — and the user file is *optional* when defaults cover everything.

```
core/src/main/resources/META-INF/tiko/defaults.yaml          # baked into core.jar
notifications/src/main/resources/META-INF/tiko/defaults.yaml # baked into notifications.jar
app/src/main/resources/application.yaml                       # user override (optional)
```

**Resolution order, per field:** user override → any module's `defaults.yaml` → `@Default(value=...)` annotation → bind error.

Two modules cannot independently claim the same `@Configuration(prefix="...")` — the runtime fails fast with a clear collision error. See `tiko-examples/06_config_multi_module/` for an end-to-end sample.

## The `tiko:` reserved namespace

Top-level `tiko:` in your YAML is reserved for framework-level configuration knobs
that Tiko itself consumes (not your `@Configuration` records). v1 defines one key:

```yaml
tiko:
  shutdownTimeout: PT5S    # event-executor graceful drain window; see events.md
```

Duration values use ISO-8601 syntax (`PT5S`, `PT30S`, `PT5M`). Phase 6 (Resiliency)
will add sibling keys (executor sizing, queue capacity, etc.). Do not declare your
own `@Configuration(prefix = "tiko")` — that prefix is the framework's.

## Nested records

A `@Configuration` record can contain plain records as field types — directly, or inside `Optional<X>`, `List<X>`, `Set<X>`, `Map<String,X>`. The codegen emits a per-record nested coercer and composes via the existing collection coercers. The nested record itself is **not** annotated `@Configuration`; it's bound by recursion under its parent's prefix.

```java
@Configuration(prefix = "app")
public record AppConfig(
        String name,
        DbConfig db,                            // direct nested
        List<Endpoint> endpoints,               // list of nested
        Set<String> allowedHosts,               // set of scalars (deduped, order-preserving)
        Map<String, FeatureFlag> flags,         // map of nested
        Optional<DbConfig> readReplica          // optional nested
        ) {}

public record DbConfig(String url, @Default("10") int max) {}
public record Endpoint(String host, int port) {}
public record FeatureFlag(boolean enabled) {}
```

## Error reporting

YAML mismatches (missing required keys, wrong types, unknown keys) fail at container startup with a single report listing every problem — never partway through serving requests. Strict-mode validation is on by default.

## `ConfigSources` factories

| Factory                                     | What it does                                                                                  |
|---------------------------------------------|-----------------------------------------------------------------------------------------------|
| `ConfigSources.classpath(path)`             | Loads a single YAML resource via the thread context classloader.                              |
| `ConfigSources.classpathAll(path)`          | Discovers every occurrence of `path` on the classloader (one per jar) and deep-merges them.   |
| `ConfigSources.file(path)`                  | Loads a YAML file from the filesystem.                                                        |
| `ConfigSources.fromMap(map)`                | In-memory source — invaluable for tests.                                                      |
| `ConfigSources.layered(a, b, c)`            | Deep-merges sources left-to-right; each subsequent source overrides earlier ones.             |

Layered example for an environment with an extra override file on top of module defaults:

```java
ConfigSource sources = ConfigSources.layered(
        ConfigSources.classpathAll("META-INF/tiko/defaults.yaml"),  // every module's defaults, merged
        ConfigSources.classpath("application.yaml"),                // app-baked baseline
        ConfigSources.file(Path.of("/etc/myapp/override.yaml")));   // op-supplied overrides

Container container = Tiko.create(sources);
```

Each layer can supply any subset of keys; missing keys fall through to the next layer down. Maps merge recursively, lists replace atomically, scalars overwrite.

> Environment variables are not a layer of their own — they are read inline via `${VAR}` interpolation inside YAML scalars.

When you pass nothing to `Tiko.create()`, the runtime still automatically discovers and layers every `META-INF/tiko/defaults.yaml` on the classpath, so `@Configuration` records bound entirely by module defaults + `@Default` annotations need no user-supplied source at all.
