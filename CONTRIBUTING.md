# Contributing to Tiko DI

Thank you for helping. This page is the short path from a clone to a pull request. The full map of
the repository, its modules and its house rules is [`CLAUDE.md`](./CLAUDE.md) together with the
path-scoped rules under [`.claude/rules/`](./.claude/rules/). They are written for AI coding agents
and read just as well for people.

## Build and test

Requires Java 21+ and Maven 3.8+. Docker runs the integration tests that start a Kafka broker.

```bash
mvn clean install                    # every module, unit tests (*Test) and integration tests (*IT)
mvn test                             # unit tests only
mvn test -Dtest=ClassName#methodName # one class or method
mvn install -DskipTests              # install without tests, e.g. before running an example
```

The Gradle examples under [`tiko-examples-gradle/`](./tiko-examples-gradle/) build against the
installed snapshot: run `mvn install -DskipTests` first, then `./gradlew build` in an example.

CI builds on JDK 21, 25, 26 and 27. Changes that touch annotation processing or `javac` are worth
running on a newer JDK too: since JDK 23, `javac` runs no annotation processor it only finds on the
classpath.

## Where things live

- `tiko-api`: the annotations and interfaces applications use.
- `tiko-processor`, `tiko-processor-core`: the compile-time processor (javac and KSP entry points)
  and the frontend-neutral model they share.
- `tiko-runtime`: `Tiko.create(...)` / `Tiko.daemon(...)`, the event bus, the aggregate container.
- `tiko-config`, `tiko-kafka`, `tiko-kafka-processor`, `tiko-coroutines`, `tiko-test`, `tiko-mcp`:
  the optional modules.
- `tiko-examples/`, `tiko-examples-gradle/`: runnable examples, which also act as behavioural tests.

[`CLAUDE.md`](./CLAUDE.md) has the dependency chain and the rules for where processor logic may live.

## Code style

[Spotless](https://github.com/diffplug/spotless) with Palantir Java Format is part of every build,
so a style violation fails `mvn compile`. Format before you commit:

```bash
mvn -pl '!tiko-bom' spotless:apply
```

On JDK 27 the check is skipped ([#465](https://github.com/tomas-samek/tiko-di/issues/465)); run the
formatter on an earlier JDK.

## Tests

JUnit 5 with AssertJ. `*Test` is a unit test (Surefire), `*IT` an integration test (Failsafe). Test
names are camelCase. Waits use Awaitility, not `Thread.sleep`. Processor behaviour is tested by
compiling sample sources. [`.claude/rules/testing.md`](./.claude/rules/testing.md) has the rest.

A behaviour change comes with a test that fails without it.

## Issues and pull requests

- **Issues** state symptoms and observable facts: what you did, what you saw, what you expected,
  with Java and Maven versions and a minimal reproducer. Leave out "probable cause" and "suggested
  fix"; [`docs/qa-playbook.md`](./docs/qa-playbook.md) explains why.
- **Branches** come off `main`: `<type>/issue-<number>-<slug>`, for example
  `fix/issue-123-close-on-failure`.
- **Commits** are one conventional-commit line, `type(scope): subject (#123)`.
- **Pull requests** target `main` and stay short: a summary of what changed and why, the checks you
  ran, and `Closes #123`. Wrap annotations in backticks (`` `@Named` ``): a bare `@Name` notifies
  the GitHub user of that name.
- [`docs/issue-fix-playbook.md`](./docs/issue-fix-playbook.md) is how a filed issue gets worked.

## Security

Report a vulnerability privately as described in [`SECURITY.md`](./SECURITY.md), not as an issue.
