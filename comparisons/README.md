# DI startup comparison

Side-by-side cold-start measurement of six wiring approaches doing the same observable work as
`tiko-examples/05_multi_module`: four singletons split across two modules, the same constructor and `@PostConstruct`
`println`s, the same first-`get()` calls.

The point is to compare **what cold startup actually costs today** — `java -cp ... Main` from the user's terminal,
default JVM, default GC. Not steady-state. Not native-image. Not memory.

## Lineup

| Project     | Wiring                                        | DI style                     |
|-------------|-----------------------------------------------|------------------------------|
| `plain`     | manual `new` chain in `Main`                  | none — floor cost            |
| `tiko`      | `Tiko.create()`                               | compile-time, lazy           |
| `dagger`    | `DaggerAppComponent.create()`                 | compile-time, lazy           |
| `avaje`     | `BeanScope.builder().build()`                 | compile-time, eager          |
| `guice`     | `Guice.createInjector(...)`                   | runtime, reflection, lazy    |
| `spring`    | `new AnnotationConfigApplicationContext(...)` | runtime, reflection, eager   |
| `micronaut` | `BeanContext.run()` (inject-only)             | compile-time, eager          |

Each project is its own self-contained Maven multi-module build (own parent pom, no relation to the root reactor).
`mvn install` at the repo root does **not** descend into `comparisons/`.

## Components (identical across all seven)

- **Module A**
    - `UserRepository` — singleton; `@PostConstruct` seeds two users with a `println` per save
    - `UserService` — singleton; depends on `UserRepository`; constructor + `@PostConstruct` `println`
- **Module B**
    - `EmailSender` — singleton; `@PostConstruct` `println`
    - `NotificationService` — singleton; depends on `EmailSender`; constructor + `@PostConstruct` `println`

The `println`s are kept on purpose so every framework runs the same workload, not different IO patterns.

For the `plain` comparison the `init()` calls are made by hand. For Dagger and Guice — which don't honor
`@PostConstruct` — the `@Provides` method calls `init()` after `new`. Spring, Micronaut, and Tiko all honor Jakarta
`@PostConstruct` natively.

## How to run

```pwsh
# 1. Make sure tiko jars are installed locally (the tiko comparison depends on them).
mvn -q install -DskipTests

# 2. Build all comparisons that exist and run the bench.
pwsh comparisons/run-all.ps1

# 3. Aggregate results into a markdown table.
pwsh comparisons/analyze.ps1
```

`run-all.ps1` builds each subproject (skipping any that don't exist yet), runs `Bench` ten times in fresh JVMs with
`iter=1`, captures wall time and per-phase nanoTime CSV, and writes everything under `comparisons/results/` (
gitignored).

`analyze.ps1` reads `results/*.csv` and prints a markdown comparison table: min, median, max for each phase per
framework.

## Bench schema

Each `app/Bench.java` writes the same CSV format to **stderr** (stdout carries component `println`s):

```
iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns
```

- `create_ns` — container/injector construction
- `first_get_a_ns` — first `get(UserService)`, drives Module A singleton init
- `first_get_b_ns` — first `get(NotificationService)`, drives Module B singleton init
- `close_ns` — container shutdown (0 for `plain` and `dagger` which don't have one)

## Latest results

_Filled by hand after a fresh `analyze.ps1` run. Numbers are machine-specific — Java 21.0.9, Windows 11, default G1GC.
Re-run locally; don't take these as gospel._

Seven frameworks wired up. (HK2 lands in #40 to round out runtime-reflection.)

### Wall time (cold JVM, ms)

| framework                        |  n |   min | median |   max |
|----------------------------------|---:|------:|-------:|------:|
| _jvm baseline (`java -version`)_ | 10 |  93.5 |   94.6 | 101.0 |
| plain                            | 10 | 162.1 |  168.3 | 172.1 |
| dagger                           | 10 | 155.2 |  170.9 | 203.0 |
| tiko                             | 10 | 177.4 |  185.2 | 188.4 |
| avaje                            | 10 | 210.0 |  214.2 | 221.8 |
| guice                            | 10 | 314.5 |  338.8 | 436.9 |
| micronaut                        | 10 | 405.0 |  434.1 | 563.8 |
| spring                           | 10 | 429.9 |  440.8 | 458.2 |

### Internal phases — median ms (cold iter=0)

| framework |  n | create | first_get_a | first_get_b | close | total |
|-----------|---:|-------:|------------:|------------:|------:|------:|
| plain     | 10 |    0.0 |        33.5 |         1.3 |   0.0 |  34.7 |
| dagger    | 10 |   11.5 |        28.4 |         0.9 |   0.0 |  40.8 |
| tiko      | 10 |   25.8 |        23.5 |         0.6 |   0.7 |  50.2 |
| avaje     | 10 |  101.7 |         0.0 |         0.0 |   0.0 | 101.7 |
| guice     | 10 |  187.1 |        20.4 |         0.3 |   0.0 | 208.4 |
| micronaut | 10 |  266.6 |        16.5 |         0.7 |   7.6 | 290.4 |
| spring    | 10 |  303.7 |         0.3 |         0.1 |   1.0 | 305.2 |

> **Apples-to-apples is `total`, not `create`.** Tiko, Dagger, and Guice defer `@PostConstruct` (or its workaround) to first access — the cost splits between `create_ns` and `first_get_*_ns`. Avaje, Spring, and Micronaut eagerly run everything during context construction. Comparing only `create_ns` would unfairly flatter the lazy frameworks.

Reading:

- **Plain** is the floor — about 74 ms over `java -version` for class loading + the actual `new` calls.
- **Dagger** lands ~6 ms above plain on `total_ns` — the cheapest DI framework here. `DaggerAppComponent.create()` is essentially a constructor call: no module-discovery scan, no aggregator. On wall time Dagger is **statistically tied with plain** on this machine.
- **Tiko** adds ~16 ms over plain on `total_ns`, ~10 ms more than Dagger. Both are compile-time and lazy. The difference is `Tiko.create()` (26 ms) vs `DaggerAppComponent.create()` (12 ms): Tiko's multi-module aggregator does a `META-INF/tiko/container.properties` scan to discover module containers, which Dagger doesn't need because the `@Component` interface lists modules explicitly at compile time.
- **Avaje** sits at ~102 ms `total_ns` — between Tiko and Guice. Compile-time like Tiko/Dagger, but **eager** by default: `BeanScope.builder().build()` constructs every singleton before returning, so all the `@PostConstruct` work lands in `create_ns` and `first_get_*` are essentially Map lookups (~10 µs each). The shape is identical to Spring's, just much cheaper because the wiring is generated, not reflective.
- **Guice** is ~107 ms above Avaje on `total_ns`. Most of it is `Guice.createInjector(...)` — runtime reflection plus cglib to generate enhanced subclasses for AOP/scoping, even though we don't use either. Lazy `@Provides @Singleton` means beans are constructed on first `getInstance(...)`, so the cost still splits across `create + first_get_a`.
- **Micronaut** (using only `micronaut-inject` + `micronaut-context`, no full framework) is ~82 ms above Guice on `total_ns` despite being compile-time DI. The interesting finding: compile-time DI ≠ fast startup on its own. Micronaut generates `BeanDefinition` classes at compile time, but `BeanContext.run()` still service-loads them via `META-INF/micronaut/...`, instantiates them eagerly, and pulls in `micronaut-aop` even when no aspects are used. It's also the only framework here that does meaningful work in `close()` (~7 ms) — graceful shutdown is on by default.
- **Spring** tops the chart at ~305 ms `total_ns`. Almost all of it lives in `create_ns` because `AnnotationConfigApplicationContext` does `@ComponentScan`, classpath reflection, and eager instantiation of every singleton (including `@PostConstruct`) before returning. After that, `getBean` is essentially free — but the bill is paid.

Three clusters emerge:

- **Lean compile-time, lazy** (35–50 ms): plain, dagger, tiko
- **Compile-time, eager** (102 ms): avaje — the cleanest demonstration that "compile-time" alone doesn't predict cost; eagerness adds ~50 ms even when wiring is generated
- **Runtime/reflection or eager-with-overhead** (208–305 ms): guice, micronaut, spring

Avaje is the bridge: same compile-time wiring as Tiko/Dagger, same eager init as Spring/Micronaut, and the result lands cleanly between them. That answers the question of which axis matters more: it's not "compile-time vs runtime" — it's "lazy vs eager," with reflection vs codegen as a secondary multiplier.

## Caveats

- Single machine, default JVM, default GC. Numbers will move on different hardware.
- Ten samples per framework — enough for medians, not for tail behaviour.
- The `println` workload is part of the measurement. A framework whose factory eagerly initialises everything pays the
  IO cost during `create_ns`; one that lazy-inits pays it during `first_get_*`. Both are real costs the user sees.
- `plain` is unfair to itself: `create_ns ≈ 0` because there is no container, so all the construction cost falls into
  `first_get_a_ns`. That's not a bug; it's what no-DI looks like.
- The runner is PowerShell-only for now. A `.sh` equivalent for Linux/macOS contributors is welcome but not in scope of
  the initial setup.

## Design

See `docs/superpowers/specs/2026-05-06-startup-comparison-design.md` for the full design and the rationale behind every
choice.
