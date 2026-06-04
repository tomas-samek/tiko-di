# 14 — Profile-Based Selection

`@Component(profiles = {...})` lets you ship multiple impls of the same interface and pick
one at build time by activating a profile.

This module ships a `Greeter` interface with two impls and one consumer:

- `DevGreeter` — `@Component(profiles = {"dev"})`
- `ProdGreeter` — `@Component(profiles = {"prod"})`
- `GreetingService` — `@Component`, injects `Greeter` via constructor. Forces the
  processor to pick exactly one provider at build time.

## How activation works

Profile selection is a **build flag**, not a runtime switch. The annotation processor
reads `-Atiko.profiles=<csv>` from the compiler arguments and filters which components
land in the generated container. Switching profiles means re-compiling.

This is consistent with Tiko's compile-time-DI design — no classpath scanning, no
conditional bean factories, no runtime ambiguity-resolution.

This module's `pom.xml` wraps the flag in two Maven profiles for ergonomics:

```bash
# dev — DevGreeter is the only Greeter in the generated container
mvn -pl tiko-examples/14_profiles -P dev compile
mvn -pl tiko-examples/14_profiles -P dev exec:java \
    -Dexec.mainClass=io.tiko.examples.profiles.Main

# prod — ProdGreeter is the only Greeter in the generated container
mvn -pl tiko-examples/14_profiles -P prod compile
mvn -pl tiko-examples/14_profiles -P prod exec:java \
    -Dexec.mainClass=io.tiko.examples.profiles.Main
```

To pass the flag directly without Maven profiles, set the property the pom expects:

```bash
mvn -pl tiko-examples/14_profiles -Dtiko.profile=dev compile
```

## What you'll see

```
=== Profile-selected Greeter impl ===
Welcome:    [dev] hi world — verbose dev greeting (debug build)
```

Build with `-P prod` and the message becomes `Hello, world.` from `ProdGreeter`. The
container only sees the impl matching the active profile — the other never lands in
generated code.

## Defaults and the "no profile" case

This module declares `dev` as `<activeByDefault>true</activeByDefault>` so the reactor
build is green without any `-P` flag — running plain `mvn compile` selects `DevGreeter`.
Maven deactivates the default whenever any other profile is explicitly activated, so
`mvn -P prod` swaps cleanly to `ProdGreeter` without needing `-P !dev`.

To see what happens when no profile is selected at all — both impls visible, no
`@Named` to disambiguate — explicitly disable the default:

```bash
mvn -pl tiko-examples/14_profiles -P !dev compile
```

The build then fails with a clear compile-time diagnostic:

```
ERROR: Multiple unnamed providers for type demo.Greeter: DevGreeter, ProdGreeter
  Suggested fixes:
  1. Add @Named("...") to each and use container.get(Greeter.class, "name")
  2. Keep one provider unnamed as the default and give the others @Component(name = "...")
```

This is the same trade-off as in any DI framework — qualify or constrain — decided
at build time rather than runtime.
