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

Five of six wired up so far. The last one lands in #32 (Micronaut).

### Wall time (cold JVM, ms)

| framework                        |  n |   min | median |   max |
|----------------------------------|---:|------:|-------:|------:|
| _jvm baseline (`java -version`)_ | 10 |  92.6 |   94.3 | 102.9 |
| plain                            | 10 | 162.8 |  170.2 | 178.6 |
| tiko                             | 10 | 162.0 |  183.7 | 188.3 |
| dagger                           | 10 | 167.8 |  170.3 | 187.4 |
| guice                            | 10 | 318.5 |  342.8 | 359.4 |
| spring                           | 10 | 433.6 |  445.0 | 513.4 |

### Internal phases — median ms (cold iter=0)

| framework |  n | create | first_get_a | first_get_b | close | total |
|-----------|---:|-------:|------------:|------------:|------:|------:|
| plain     | 10 |    0.0 |        33.4 |         1.3 |   0.0 |  34.7 |
| tiko      | 10 |   24.5 |        23.1 |         0.7 |   0.7 |  49.0 |
| dagger    | 10 |   11.8 |        28.5 |         0.8 |   0.0 |  41.1 |
| guice     | 10 |  186.9 |        20.2 |         0.2 |   0.0 | 207.1 |
| spring    | 10 |  306.8 |         0.3 |         0.1 |   1.0 | 308.2 |

> **Apples-to-apples is `total`, not `create`.** Tiko, Dagger, and Guice defer `@PostConstruct` (or its workaround) to first access — the cost splits between `create_ns` and `first_get_*_ns`. Spring eagerly runs everything during context construction. Comparing only `create_ns` would unfairly flatter the lazy frameworks.

Reading:

- **Plain** is the floor — about 76 ms over `java -version` for class loading + the actual `new` calls.
- **Tiko** adds ~14 ms over plain at the `total_ns` level. The internal breakdown shows where it lives: `Tiko.create()` is ~25 ms (aggregator + per-module container construction + classpath scan), then `first_get_a` is ~23 ms which is essentially the same UserRepository/UserService construction that plain pays during its first `get`. So Tiko's *added* cost over plain is the create step plus a tiny close step; the rest of the work is workload, not framework.
- **Dagger** lands ~6 ms above plain — the cheapest DI framework here by a clear margin, and ~8 ms cheaper than Tiko on `total_ns`. Both are compile-time, but Dagger's generated code is leaner: `DaggerAppComponent.create()` is essentially a constructor call, no module-discovery scan, no aggregator. That's the gap on `create_ns` (12 ms vs 25 ms). On wall time Dagger is **statistically tied with plain** on this machine.
- **Guice** lands ~170 ms above Dagger on `total_ns`. Most of that is `Guice.createInjector(...)` — Guice does runtime reflection and uses cglib to generate enhanced subclasses for AOP/scoping, even though we don't use either. With `@Provides @Singleton`, beans are constructed lazily on first `getInstance(...)`, so the cost still splits across `create + first_get_a` (the second `getInstance` for an already-resolved type is essentially free).
- **Spring** is ~2.4× slower wall-clock than Tiko on cold start. Almost all of the cost lives in `create_ns` because `AnnotationConfigApplicationContext` does `@ComponentScan`, classpath reflection, and eagerly instantiates every singleton (including `@PostConstruct`) before returning. After that, `getBean` is essentially free — but the user has already paid the bill.

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
