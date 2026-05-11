# Release Notes

All notable changes to **tiko-di** are documented in this file. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it leaves alpha.

## [Unreleased]

_Phase 2 work in progress — see the [Roadmap](README.md#roadmap) and [open issues](https://github.com/tomas-samek/tiko-di/issues)._

### Added

- **`@Pick` composes with `@Named`**. Combining `@Pick(Impl.class) @Named("...")` on the same parameter narrows by impl class first, then disambiguates by name. The right pattern for picking among multiple `@Produces` methods that return the same concrete subtype with different names. The previous "the two cannot be combined" rule has been removed; both validator and codegen now resolve the composite (impl, name) lookup. Also fixes a latent codegen bug where `@Pick` against a `@Produces` target without `@Named` referenced a non-existent component getter.
- **Nested records inside `@Configuration` are now supported** ([#17](https://github.com/tomas-samek/tiko-di/issues/17)). A `@Configuration` record can contain plain records as field types — directly, or inside `Optional<X>` / `List<X>` / `Map<String,X>`. The processor emits a per-nested-record coercer (`<Record>NestedCoercer_<hash>`) that composes with the existing collection coercers. Nested records are not themselves `@Configuration`-annotated; they bind by recursion under the parent's prefix.
- **Module-baked defaults + single external override** ([#18](https://github.com/tomas-samek/tiko-di/issues/18)). Each module can ship its own `META-INF/tiko/defaults.yaml` inside its jar; Tiko discovers every such file on the classpath, deep-merges them, and layers the user-supplied `ConfigSource` on top. The user override is optional — `Tiko.create()` (no args) is now allowed when module defaults plus `@Default` annotations cover every required field. Resolution order per field: user override → any module's `defaults.yaml` → `@Default(value=...)` → bind error. New helper `ConfigSources.classpathAll(...)` enumerates and merges every classpath occurrence of a resource. End-to-end sample in `tiko-examples/06_config_multi_module/`.
- **Cross-module `@Configuration` prefix collision check** ([#18](https://github.com/tomas-samek/tiko-di/issues/18)). Two modules independently declaring records with the same `@Configuration(prefix="...")` previously collapsed silently (last-binder-wins, undefined order). The runtime now fails fast with a clear message naming both record types so the conflict is fixable.

### Changed

- **`Tiko.create()` (no args) no longer fails up-front when `@Configuration` records are declared** ([#18](https://github.com/tomas-samek/tiko-di/issues/18)). The previous "you forgot to pass a `ConfigSource`" check is replaced by per-field validation during binding — module-baked defaults and `@Default` annotations are tried first, and missing values surface with the specific field name. To keep the old behavior, pass an empty `ConfigSource` explicitly or rely on the bind-time errors.
- **`@PreDestroy` now fires for REQUEST and EVENT scopes** ([#57](https://github.com/tomas-samek/tiko-di/issues/57), option B). Previously the runtime cleared the scope map without invoking destroy hooks — silently dropping user cleanup. Hooks now fire in reverse-creation (LIFO) order at scope exit, after the corresponding `RequestEndingEvent` / `EventEndingEvent` is published. Each hook is wrapped so a failure logs and continues instead of skipping the rest of teardown.
- **`AutoCloseable` cleanup convention** ([#57](https://github.com/tomas-samek/tiko-di/issues/57)). A `@Component` (or a type returned by a `@Produces` method) that implements `AutoCloseable` and declares no explicit `@PreDestroy` gets `close()` called automatically at scope teardown — no annotation required. Lets `@Produces` factories return third-party closeables (`HikariDataSource`, `HttpClient`, `KafkaProducer`, …) without a wrapper `@Component`. Explicit `@PreDestroy` always wins to avoid double-cleanup.
- **Compile-time leak warning** ([#57](https://github.com/tomas-samek/tiko-di/issues/57)). The processor warns when a `@Component` holds a field of an `AutoCloseable` type but the bean has neither a `@PreDestroy` nor implements `AutoCloseable` itself. Suppressible with `@SuppressWarnings("resource")` on the field or class.

  Thanks to [@SentryMan](https://github.com/SentryMan) for the discussion in [#57](https://github.com/tomas-samek/tiko-di/issues/57) and the [avaje-inject prior-art pointer](https://github.com/avaje/avaje-inject/pull/968) — useful framing while picking the position.

## [0.1.0] — 2026-05-08

First alpha release. Marks completion of Phase 1 work plus the basic Maven archetype for project scaffolding. Suitable for early-adopter experimentation; production use should wait for Phase 2.

### Added

**Core dependency injection:**
- `@Component`, `@Inject`, `@Named`, `@Produces` annotations with full compile-time validation
- SINGLETON / REQUEST / EVENT / PROTOTYPE scopes with cross-scope proxy generation
- `@PostConstruct` / `@PreDestroy` lifecycle hooks
- `container.pick(Class)` fluent lookup API with name resolution, lazy providers, and fallbacks
- Multi-module aggregation via `AggregatingContainer`

**Configuration:**
- `@Configuration` records with typed YAML binding
- Generated per-record binders, `${VAR}` interpolation, layered `ConfigSources`
- Strict-mode validation that fails fast on missing or malformed keys
- See [#15](https://github.com/tomas-samek/tiko-di/issues/15) for v1 scope; nested-record codegen is tracked for Phase 2 ([#17](https://github.com/tomas-samek/tiko-di/issues/17))

**Events:**
- `@EventHandler` for receiving events
- In-memory event bus (`LocalEventBus` in `tiko-runtime`) for single-instance deployments
- Lifecycle events (`ApplicationStartedEvent`, `RequestStartedEvent`, etc.) automatically published around scope boundaries — exactly once across single- and multi-module setups ([#45](https://github.com/tomas-samek/tiko-di/issues/45))
- `@EventTrigger` chains for declarative event workflows, with guards, spread, and async support
- Full origin tracking via `Event<?>`
- Handler-exception isolation via configurable `ErrorHandler` hook (default: JUL `WARNING`); user-supplied handler routed through `TikoOptions.errorHandler(...)` ([#44](https://github.com/tomas-samek/tiko-di/issues/44))
- `@EventHandler(async = true)` honoured via bounded `ThreadPoolExecutor`; user-supplied executor via `TikoOptions.eventExecutor(...)` propagated through both single- and multi-module paths ([#43](https://github.com/tomas-samek/tiko-di/issues/43), [#51](https://github.com/tomas-samek/tiko-di/issues/51))

**Lifecycle:**
- `Container.start()` exposed on the public interface; idempotent CAS guard
- `Container.shutdown()` is idempotent and race-free with concurrent `get()` calls ([#47](https://github.com/tomas-samek/tiko-di/issues/47))
- Post-shutdown `get()` throws `IllegalStateException`; `@PreDestroy` methods can call `container.get(...)` via thread-local bypass

**Tooling:**
- `tiko-archetype` Maven archetype for scaffolding new tiko-di projects ([#20](https://github.com/tomas-samek/tiko-di/issues/20))
  - Minimal scaffold: pom + `Main` + one `@Component` + AI-assistant context files (`CLAUDE.md`, `.ai-skills/SKILL.md`, `.cursor/rules/tiko.md`)
  - `mvn exec:java` runs end-to-end out of the box
- Worked examples under [`tiko-examples/`](https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples) covering DI, configuration, events, API/impl split, and multi-module aggregation
- Side-by-side cold-start comparison harness in [`comparisons/`](https://github.com/tomas-samek/tiko-di/tree/main/comparisons): plain / Tiko / Dagger / Avaje / HK2 / Guice / Micronaut / Spring

### Changed

- Framework no longer requires any logging-binding dependency. Internal logging routed through `java.util.logging`; the default `ErrorHandler` writes through JUL at `WARNING`. Users on slf4j stacks supply their own `ErrorHandler` (see README "Error handling"). ([#53](https://github.com/tomas-samek/tiko-di/issues/53))

### Known limitations

- **No Maven Central distribution yet** — build from source required. Tracked as Phase 5 work.
- **Nested records inside `@Configuration` fields, lists, and maps** are not yet supported by the generated binder. Workaround: declare nested sections as separate `@Configuration` records with their own `prefix`. Tracked in [#17](https://github.com/tomas-samek/tiko-di/issues/17).
- **`container.get(Class, String)` and `container.get(Class)` use slightly different matching strategies** (`isAssignableFrom` vs. exact class/interface). The asymmetry is intentional for now; may be unified in a future release.
- See the full [open-issues list](https://github.com/tomas-samek/tiko-di/issues) for everything else.

### Installation

Tiko is **not yet on Maven Central**. To use this release, build from source:

```bash
git clone --branch v0.1.0 https://github.com/tomas-samek/tiko-di.git
cd tiko-di
mvn clean install
```

Artifacts will be available in your local Maven repository under `io.tiko:tiko-*:0.1.0`.

For new projects, use the archetype:

```bash
mvn archetype:generate \
    -DarchetypeGroupId=io.tiko \
    -DarchetypeArtifactId=tiko-archetype \
    -DarchetypeVersion=0.1.0 \
    -DarchetypeCatalog=local \
    -DgroupId=com.example \
    -DartifactId=my-app
```

See the [README Quick Start](README.md#quick-start) for the full walkthrough including the JDK 23+ annotation processing notes.

[Unreleased]: https://github.com/tomas-samek/tiko-di/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/tomas-samek/tiko-di/releases/tag/v0.1.0
