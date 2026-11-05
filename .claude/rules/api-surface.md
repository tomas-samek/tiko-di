---
paths:
  - "tiko-api/**"
  - "**/annotations/**/*.java"
  - "tiko-test/src/main/java/**"
---

# Public API: annotations and interfaces

## Annotation Retention

- Annotations the processor reads are `RetentionPolicy.SOURCE`. There is no good
  reason for a compile-time concern to leak into runtime bytecode.
- `@PostConstruct` and `@PreDestroy` are `RUNTIME` because the container invokes
  them via generated code at runtime — that is the bar for keeping `RUNTIME`.
- When adding a new annotation, audit it: if the bytecode doesn't read it, it must
  be `SOURCE`.

## Interface Default Methods

Default methods on `tiko-api` interfaces are one-liners — typically a delegation
to another method on the same interface, or a return of a singleton instance.
Executable fallback logic (e.g. a JUL-backed error handler) goes in a
package-private named class (see `FallbackErrorHandler`), not inline in the
interface body. Keeps the API surface free of implementation.
