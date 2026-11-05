---
paths:
  - "**/*.java"
---

# Java code in every module

## Java 21+ Feature Usage

- **Records** — for immutable DTOs, descriptors, lifecycle events, error contexts.
- **Sealed classes / interfaces** — for closed type hierarchies (e.g. `ErrorContext`
  + non-sealed `TransportError` escape hatch).
- **Pattern matching for `instanceof`** — wherever a downcast follows a type check.
  No `if (x instanceof Foo) { Foo f = (Foo) x; ... }` patterns.
- **Switch expressions** — arrow syntax for non-trivial dispatch.
- **Text blocks** — for multi-line strings, especially in code-generation templates
  and diagnostic messages.
- **`var` for local variables** — prefer `var` when the right-hand side makes the
  type obvious (constructors, static factories, named getters returning typed
  results). Don't use `var` when the type isn't visible at the call site. Fields,
  parameters, and return types stay explicit (the language requires it).
- **Enum constants over string `.name()`** — compare
  `getKind() == ElementKind.RECORD_COMPONENT`, never
  `getKind().name().equals("RECORD_COMPONENT")`. Applies to any enum.
- **For-loops vs. Streams.** For-loops are correct when: the body throws checked
  exceptions (Stream APIs can't propagate them cleanly), the loop has side effects
  on a shared builder, recursion with early-return is needed, or reverse iteration
  / explicit indexing matters. Streams are correct for pure
  filter/map/reduce/collect pipelines.

## Method Length

Methods longer than ~50 LOC that mix multiple distinct concerns (e.g. reading
three resources, decoding plus validating, parsing plus dispatching) should be
split into private helpers along concern boundaries. Helpers should be named for
what they do, not where they're called from.
