# Copilot instructions

This project is a Kotlin service on Tiko DI, a compile-time dependency injection framework whose
processor runs on KSP. **Read [`CLAUDE.md`](../CLAUDE.md) for the full conventions before
suggesting code.** The summary below is a refresher; CLAUDE.md is authoritative.

## Patterns to follow

```kotlin
@Component(scope = Scope.SINGLETON)
class FooService(private val repo: BarRepository) {
    // ...
}
```

- Constructor injection through the primary constructor, never field injection.
- Components declare their scope.
- `@TestComponent` for test fixtures (from `tiko-test`); the class it replaces is `open`.

## Build commands

- `./gradlew build`: compiles (KSP runs the processor) and runs the tests
- `./gradlew test`: tests only
