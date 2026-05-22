# Junie guidelines

This project uses Tiko DI — a compile-time DI framework for Java 21+. The
canonical rules live in [`CLAUDE.md`](../CLAUDE.md); read it before
generating code. The bullets below are a refresher.

## Rules

- Constructor injection only — `@Inject` on the constructor.
- Every `@Component` declares a scope: SINGLETON, REQUEST, EVENT, or PROTOTYPE.
- Configuration uses `@Configuration` records bound from YAML.
- Event handlers use `@EventHandler` on methods of `@Component` classes.
- Test fakes use `@TestComponent` from `tiko-test`.

## Build commands

- `mvn compile` — annotation processing runs here
- `mvn test` — full test run
- `mvn exec:java -Dexec.mainClass=${package}.Main` — run example
