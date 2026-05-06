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
| `hk2`       | `ServiceLocatorUtilities.bind(...)`           | runtime, reflection, lazy    |
| `spring`    | `new AnnotationConfigApplicationContext(...)` | runtime, reflection, eager   |
| `micronaut` | `BeanContext.run()` (inject-only)             | compile-time, eager          |

Each project is its own self-contained Maven multi-module build (own parent pom, no relation to the root reactor).
`mvn install` at the repo root does **not** descend into `comparisons/`.

## Components (identical across all eight)

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

All eight frameworks wired up.

### Wall time (cold JVM, ms)

| framework                        |  n |   min | median |   max |
|----------------------------------|---:|------:|-------:|------:|
| _jvm baseline (`java -version`)_ | 10 |  94.5 |   95.5 | 102.7 |
| plain                            | 10 | 158.8 |  170.7 | 200.7 |
| dagger                           | 10 | 167.8 |  169.3 | 185.8 |
| tiko                             | 10 | 164.4 |  185.4 | 200.9 |
| avaje                            | 10 | 199.8 |  207.1 | 270.7 |
| hk2                              | 10 | 270.0 |  291.4 | 327.9 |
| guice                            | 10 | 315.9 |  340.8 | 350.8 |
| micronaut                        | 10 | 400.2 |  407.5 | 448.8 |
| spring                           | 10 | 437.3 |  467.9 | 489.0 |

### Internal phases — median ms (cold iter=0), ordered by ascending `total_ns`

| framework |  n | create | first_get_a | first_get_b | close | total |
|-----------|---:|-------:|------------:|------------:|------:|------:|
| plain     | 10 |    0.0 |        35.2 |         1.3 |   0.0 |  36.5 |
| dagger    | 10 |   11.7 |        28.2 |         0.8 |   0.0 |  40.8 |
| tiko      | 10 |   25.1 |        23.0 |         0.7 |   0.7 |  49.6 |
| avaje     | 10 |   99.0 |         0.0 |         0.0 |   0.0 |  99.0 |
| hk2       | 10 |  130.5 |        20.2 |         1.2 |   2.8 | 156.2 |
| guice     | 10 |  181.6 |        20.6 |         0.3 |   0.0 | 202.5 |
| micronaut | 10 |  250.3 |        15.4 |         0.6 |   7.2 | 273.3 |
| spring    | 10 |  323.2 |         0.3 |         0.1 |   1.0 | 324.4 |

> **Apples-to-apples is `total`, not `create`.** Tiko, Dagger, Guice, and HK2 defer `@PostConstruct` to first access — the cost splits between `create_ns` and `first_get_*_ns`. Avaje, Spring, and Micronaut eagerly run everything during context construction. Comparing only `create_ns` would unfairly flatter the lazy frameworks.

Reading:

- **Plain** is the floor — about 75 ms over `java -version` for class loading + the actual `new` calls.
- **Dagger** lands ~4 ms above plain on `total_ns` — the cheapest DI framework here. `DaggerAppComponent.create()` is essentially a constructor call: no module-discovery scan, no aggregator. On wall time Dagger is **statistically tied with plain** on this machine.
- **Tiko** adds ~13 ms over plain on `total_ns`, ~9 ms more than Dagger. Both are compile-time and lazy. The difference is `Tiko.create()` (25 ms) vs `DaggerAppComponent.create()` (12 ms): Tiko's multi-module aggregator does a `META-INF/tiko/container.properties` scan to discover module containers, which Dagger doesn't need because the `@Component` interface lists modules explicitly at compile time.
- **Avaje** sits at ~99 ms `total_ns` — about 50 ms above Tiko. Compile-time like Tiko/Dagger, but **eager** by default: `BeanScope.builder().build()` constructs every singleton before returning, so all the `@PostConstruct` work lands in `create_ns` and `first_get_*` are essentially Map lookups (~10 µs each). The shape is identical to Spring's, just much cheaper because the wiring is generated, not reflective.
- **HK2** at ~156 ms `total_ns` is the cheapest runtime-reflection framework here, ~50 ms below Guice. Like Guice it's lazy (`bindAsContract().in(Singleton.class)` defers construction to first `getService(...)`), and like Guice it pulls in a bytecode-manipulation library (javassist instead of cglib), but the HK2 core is leaner. It's also the second framework here that does real work in `close()` (~3 ms shutdown).
- **Guice** is ~46 ms above HK2 on `total_ns`. Most of the cost is `Guice.createInjector(...)` — runtime reflection plus cglib for proxy/AOP infrastructure, even though we don't use either. Lazy `@Provides @Singleton` means construction still splits across `create + first_get_a`, like HK2.
- **Micronaut** (using only `micronaut-inject` + `micronaut-context`, no full framework) is ~71 ms above Guice on `total_ns` despite being compile-time DI. The interesting finding: compile-time DI ≠ fast startup on its own. Micronaut generates `BeanDefinition` classes at compile time, but `BeanContext.run()` still service-loads them via `META-INF/micronaut/...`, instantiates them eagerly, and pulls in `micronaut-aop` even when no aspects are used. Also the framework with the largest `close()` cost here (~7 ms) — graceful shutdown is on by default.
- **Spring** tops the chart at ~324 ms `total_ns`. Almost all of it lives in `create_ns` because `AnnotationConfigApplicationContext` does `@ComponentScan`, classpath reflection, and eager instantiation of every singleton (including `@PostConstruct`) before returning. After that, `getBean` is essentially free — but the bill is paid.

Four clusters emerge:

- **Lean compile-time, lazy** (37–50 ms): plain, dagger, tiko
- **Compile-time, eager** (99 ms): avaje — the cleanest demonstration that "compile-time" alone doesn't predict cost; eagerness adds ~50 ms even when wiring is generated
- **Runtime reflection, lazy** (156–203 ms): hk2, guice — same conceptual model, HK2 is meaningfully cheaper here
- **Eager-with-overhead** (273–324 ms): micronaut, spring

The dominant axis for cold-start cost is **eager vs lazy init**. "Compile-time vs runtime" matters as a secondary multiplier — within each laziness class the compile-time framework is cheaper (Tiko < Guice; Avaje < Spring). But Avaje (compile-time + eager) is slower than Guice (runtime + lazy), because eagerness costs more than reflection saves at this scale.

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
