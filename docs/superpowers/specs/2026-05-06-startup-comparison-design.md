# DI framework startup comparison — design

## Goal

Side-by-side cold-start measurement of six wiring approaches, all configured to do the **same observable work** as `tiko-examples/05_multi_module`. Output is honest comparison data plus visible source code so a reader can see how each framework expresses the same component graph.

The result should answer: **for a small two-module application with four singletons, what does cold startup cost in each framework, and what does the wiring code look like?**

## Lineup

| Project | Wiring | DI style | Notes |
|---|---|---|---|
| `plain` | manual `new` chain in `Main` | none | floor cost — measures the JVM + class loading + the actual work, no framework overhead |
| `tiko` | `Tiko.create()` | compile-time | newly-written project under `comparisons/tiko/` mirroring `05_multi_module`'s 2-module/4-component shape; depends on locally-installed `io.tiko:*:0.1.0` |
| `dagger` | `DaggerAppComponent.create()` | compile-time | one `@Component`, two `@Module`s |
| `guice` | `Guice.createInjector(new ModuleA(), new ModuleB())` | runtime, reflection | two `AbstractModule`s |
| `spring` | `new AnnotationConfigApplicationContext(AppConfig.class)` | runtime, reflection | spring-context 6.1.x; **no Spring Boot**, no auto-config |
| `micronaut` | `BeanContext.run()` | compile-time | `micronaut-inject` + `micronaut-inject-java` only — **no ApplicationContext, no full framework** |

## Layout

```
comparisons/
├── README.md             # what is measured, how to reproduce, results table refresh recipe
├── run-all.ps1           # builds each project, runs N cold JVMs, captures CSV + wall time
├── analyze.ps1           # aggregates results/*.csv → markdown comparison table
├── results/              # gitignored, regenerated per run
├── plain/
├── tiko/
├── dagger/
├── guice/
├── spring/
└── micronaut/
```

Each subdirectory is its own self-contained Maven multi-module project (`module-a`, `module-b`, `app`) with its own parent `pom.xml`. None of them are part of the root reactor — root `mvn install` ignores `comparisons/`.

## Component shape (identical across all six)

The four components mirror `tiko-examples/05_multi_module` exactly so the workload is constant:

- **Module A**
  - `UserRepository` — singleton; `@PostConstruct` seeds two users with `println` per save (matches Tiko's "Saved user: ..." output)
  - `UserService` — singleton; depends on `UserRepository`; constructor `println` + `@PostConstruct` `println`
- **Module B**
  - `EmailSender` — singleton; `@PostConstruct` `println`
  - `NotificationService` — singleton; depends on `EmailSender`; constructor `println` + `@PostConstruct` `println`

For `plain`, the same component classes exist but without annotations — the `Main` class calls `new` and `init()` in order.

For `dagger`, `@PostConstruct` is not native: the `@Module`'s `@Provides` method calls `init()` after `new`. Documented in the comparison as a wiring difference, not a component-shape difference.

For `micronaut` and `spring`, Jakarta annotations (`jakarta.annotation.PostConstruct`).

For `guice`, the same as Dagger — call `init()` from the `@Provides` method.

## Bench harness (identical CSV schema per project)

Each `app` module ships `Bench.java`:

```
iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns
```

- `create_ns` — time to construct the container/injector
- `first_get_a_ns` — time to retrieve `UserService` (drives Module A singleton init)
- `first_get_b_ns` — time to retrieve `NotificationService` (drives Module B singleton init)
- `close_ns` — container shutdown (0 for `plain` and `dagger`)
- `total_ns` — sum of the four

CSV is written to **stderr** so component `println`s on stdout do not interleave.

`Bench.main` accepts an iteration count (default 5). For cold-start measurement we invoke each `Bench` with `iter=1` from a fresh JVM, ten times.

## Runner

`run-all.ps1`:

1. Optional flag `-InstallTiko` runs `mvn -q install -DskipTests` at repo root first (so the `tiko/` comparison can resolve `io.tiko:*:0.1.0`). Skipped by default — most users will already have a fresh install.
2. For each project in `{plain, tiko, dagger, guice, spring, micronaut}`:
   - `mvn -q -f comparisons/<name>/pom.xml package`
   - Run `Bench` ten times via `cmd /c` wrappers (PowerShell's redirection is unreliable for native processes), capturing wall time externally with `Stopwatch` and per-phase nanoTime CSV from stderr.
   - Append rows to `results/<name>.csv`.
3. Also runs a JVM-only baseline (`java -version` × 10) for context, written to `results/jvm.csv`.

`analyze.ps1` reads `results/*.csv` and emits a markdown table with **min, median, max** for: wall time, `create_ns`, `first_get_a_ns`, `first_get_b_ns`, `close_ns`, `total_ns`. Plus a brief one-liner per framework noting which phase dominates.

## Versions and JVM settings

- **Java** 21 (matches dev environment); `maven.compiler.release=17` for everything except where a framework forces newer.
- Framework versions: pick the latest stable at implementation time, pin in each comparison's `pom.xml`, and record the resolved version in the README result table. Targets at time of writing:
  - **Spring** `org.springframework:spring-context` 6.1.x or 6.2.x (Jakarta).
  - **Dagger** `com.google.dagger:dagger` 2.5x.x + `dagger-compiler` on annotation processor path.
  - **Guice** `com.google.inject:guice` 7.x.
  - **Micronaut** `io.micronaut:micronaut-inject` 4.x + `micronaut-inject-java` annotation processor. Container created via `BeanContext.run()`. Verify with `mvn dependency:tree` that no server-side modules are pulled in.
- **Plain**: no deps, no parent.
- All runs use default JVM flags: no CDS tweaks, no AOT cache, no JIT tuning. The point is "what does someone running `java -cp ... Main` actually see today."

## What is and is not measured

**In scope:**
- Wall time of a `java` invocation that creates a container, gets two beans, closes
- Per-phase breakdown of internal work via `nanoTime`
- Reasonable cold-start variance (10 samples per framework)

**Out of scope:**
- GraalVM native-image
- JMH steady-state benchmarks (would hide the cold-start cost we want to see)
- Memory footprint
- Spring Boot or Helidon SE (server frameworks, not DI containers)
- Throughput, request latency, anything past startup

## README

`comparisons/README.md` covers:
- One-paragraph statement of what's being compared and why
- How to run (`pwsh run-all.ps1; pwsh analyze.ps1`)
- Latest result table (refreshed manually; the runner does not auto-commit)
- Caveats: single machine, default JVM, default GC, six samples is enough for medians but not tails
- A pointer to each framework's `app/Main.java` so a reader can compare wiring source side-by-side

## Risks and mitigations

- **Micronaut bringing more than expected.** Verify with `mvn dependency:tree` that the resolved graph for `comparisons/micronaut` contains only `micronaut-inject`, `micronaut-context` (transitive), and JDK. If a server-side dep slips in, the comparison is invalid.
- **Dagger / Guice `@PostConstruct` semantics.** They don't honor it. The `@Provides` method must call `init()` explicitly. Document this in the README as the one place the comparison is not pixel-identical.
- **Spring's classpath scanning vs explicit config.** Use `@Configuration` with explicit `@ComponentScan(basePackages = {"a","b"})` to mirror Tiko's two-module discovery — not single-package scans, not `AnnotationConfigApplicationContext(Class<?>...)` with explicit listing.
- **Plain JVM is unfair to itself.** With no DI, `create_ns` is essentially zero, and `first_get_a_ns` swallows all the construction cost. Document this in the analysis output rather than trying to "fix" it.

## Out of scope (deferred)

- Running on multiple machines / CI
- Auto-publishing results to a leaderboard or website
- Native-image cold-start (Phase 5 of the public roadmap mentions GraalVM separately)
- Adding a seventh / eighth framework
