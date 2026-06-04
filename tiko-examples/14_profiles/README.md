# 14 — Profile-Based Selection

`@Component(profiles = {...})` lets you ship multiple impls of the same interface and pick
one at build time by activating a profile.

This module ships a `Greeter` interface with two impls:

- `DevGreeter` — `@Component(profiles = {"dev"})`
- `ProdGreeter` — `@Component(profiles = {"prod"})`

## How activation works

Profile selection is a **build flag**, not a runtime switch. The annotation processor
reads `-Atiko.profiles=<csv>` from the compiler arguments and filters which components
land in the generated container. Switching profiles means re-compiling.

This is consistent with Tiko's compile-time-DI design — no classpath scanning, no
conditional bean factories, no runtime ambiguity-resolution.

This module's `pom.xml` wraps the flag in two Maven profiles for ergonomics:

```bash
# dev — only DevGreeter in the generated container
mvn -pl tiko-examples/14_profiles -P dev compile
mvn -pl tiko-examples/14_profiles -P dev exec:java \
    -Dexec.mainClass=io.tiko.examples.profiles.Main

# prod — only ProdGreeter in the generated container
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
Bound impl: DevGreeter
Greet:      [dev] hi world — verbose dev greeting (debug build)
```

Build with `-P prod` and the bound impl becomes `ProdGreeter`. The container only
sees the impl matching the active profile — the other never lands in generated code.

## With no profile selected

If you skip `-P` entirely, **both** impls land in the container — `@Component`
without a matching active profile and without any active profiles set is treated
as always-active. The container then picks one of the two (currently the first
encountered) for direct `container.get(Greeter.class)` lookups. The demo still
runs, but the selection isn't meaningful.

> **Current limitation:** constructor injection of a profile-keyed type from another
> `@Component` consumer doesn't yet honor profile filtering — the factory generation
> uses the unfiltered component list when resolving the inject target, while the
> container itself correctly excludes profile-mismatched impls. Tracked in
> [#272](https://github.com/tomas-samek/tiko-di/issues/272). For now, demonstrate
> profile selection via direct `container.get(Greeter.class)` only.
