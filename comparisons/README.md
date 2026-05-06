# DI startup comparison

Side-by-side cold-start measurement of six wiring approaches doing the same observable work as
`tiko-examples/05_multi_module`: four singletons split across two modules, the same constructor and `@PostConstruct`
`println`s, the same first-`get()` calls.

The point is to compare **what cold startup actually costs today** — `java -cp ... Main` from the user's terminal,
default JVM, default GC. Not steady-state. Not native-image. Not memory.

## Lineup

| Project     | Wiring                                        | DI style            |
|-------------|-----------------------------------------------|---------------------|
| `plain`     | manual `new` chain in `Main`                  | none — floor cost   |
| `tiko`      | `Tiko.create()`                               | compile-time        |
| `dagger`    | `DaggerAppComponent.create()`                 | compile-time        |
| `guice`     | `Guice.createInjector(...)`                   | runtime, reflection |
| `spring`    | `new AnnotationConfigApplicationContext(...)` | runtime, reflection |
| `micronaut` | `BeanContext.run()` (inject-only)             | compile-time        |

Each project is its own self-contained Maven multi-module build (own parent pom, no relation to the root reactor).
`mvn install` at the repo root does **not** descend into `comparisons/`.

## Components (identical across all six)

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

All six frameworks wired up.

### Wall time (cold JVM, ms)

| framework                        |  n |   min | median |   max |
|----------------------------------|---:|------:|-------:|------:|
| _jvm baseline (`java -version`)_ | 10 |  96.4 |   97.9 | 107.4 |
| plain                            | 10 | 144.9 |  168.9 | 177.2 |
| dagger                           | 10 | 154.0 |  170.8 | 219.3 |
| tiko                             | 10 | 170.8 |  184.7 | 189.6 |
| guice                            | 10 | 327.0 |  341.3 | 387.7 |
| micronaut                        | 10 | 390.2 |  404.8 | 418.9 |
| spring                           | 10 | 431.6 |  440.7 | 466.9 |

### Internal phases — median ms (cold iter=0)

| framework |  n | create | first_get_a | first_get_b | close | total |
|-----------|---:|-------:|------------:|------------:|------:|------:|
| plain     | 10 |    0.0 |        33.5 |         1.3 |   0.0 |  34.9 |
| dagger    | 10 |   11.3 |        27.9 |         0.8 |   0.0 |  40.2 |
| tiko      | 10 |   24.0 |        22.1 |         0.6 |   0.7 |  46.8 |
| guice     | 10 |  189.8 |        22.7 |         0.3 |   0.0 | 209.7 |
| micronaut | 10 |  247.4 |        16.0 |         0.7 |   7.3 | 271.9 |
| spring    | 10 |  301.9 |         0.3 |         0.1 |   1.0 | 303.2 |

> **Apples-to-apples is `total`, not `create`.** Tiko, Dagger, and Guice defer `@PostConstruct` (or its workaround) to first access — the cost splits between `create_ns` and `first_get_*_ns`. Spring and Micronaut eagerly run everything during context construction. Comparing only `create_ns` would unfairly flatter the lazy frameworks.

Reading:

- **Plain** is the floor — about 71 ms over `java -version` for class loading + the actual `new` calls.
- **Dagger** lands ~5 ms above plain on `total_ns` — the cheapest DI framework here. `DaggerAppComponent.create()` is essentially a constructor call: no module-discovery scan, no aggregator. On wall time Dagger is **statistically tied with plain** on this machine.
- **Tiko** adds ~12 ms over plain on `total_ns`, ~7 ms more than Dagger. Both are compile-time. The difference is `Tiko.create()` (24 ms) vs `DaggerAppComponent.create()` (11 ms): Tiko's multi-module aggregator does a `META-INF/tiko/container.properties` scan to discover module containers, which Dagger doesn't need because the `@Component` interface lists modules explicitly at compile time.
- **Guice** is ~170 ms above Tiko on `total_ns`. Most of it is `Guice.createInjector(...)` — runtime reflection plus cglib to generate enhanced subclasses for AOP/scoping, even though we don't use either. Lazy `@Provides @Singleton` means beans are constructed on first `getInstance(...)`, so the cost still splits across `create + first_get_a`.
- **Micronaut** (using only `micronaut-inject` + `micronaut-context`, no full framework) is ~62 ms above Guice on `total_ns` despite being compile-time DI. The interesting finding: compile-time DI ≠ fast startup on its own. Micronaut generates `BeanDefinition` classes at compile time, but `BeanContext.run()` still service-loads them via `META-INF/micronaut/...`, instantiates them eagerly, and pulls in `micronaut-aop` even when no aspects are used. It's also the only framework here that does meaningful work in `close()` (~7 ms) — graceful shutdown is on by default.
- **Spring** tops the chart at ~303 ms `total_ns`. Almost all of it lives in `create_ns` because `AnnotationConfigApplicationContext` does `@ComponentScan`, classpath reflection, and eager instantiation of every singleton (including `@PostConstruct`) before returning. After that, `getBean` is essentially free — but the bill is paid.

The runtime/reflection/scan frameworks (Spring, Guice, Micronaut) cluster in the 200–300 ms `total_ns` band; the compile-time + lean ones (Dagger, Tiko, plain) cluster in the 35–47 ms band. Micronaut is the surprise — compile-time, but its eager init + service-loader scan land it with the runtime crowd.

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
