# Release Notes

All notable changes to **tiko-di** are documented in this file. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it leaves alpha.

## [Unreleased]

_Phase 2 work in progress — see the [Roadmap](README.md#roadmap) and [open issues](https://github.com/tomas-samek/tiko-di/issues)._

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
- In-memory event bus (`tiko-event-local`) for single-instance deployments
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
