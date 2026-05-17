# 11 — Custom logger routing (slf4j + logback)

Demonstrates how to route tiko's framework logs through slf4j + a logback backend by
adding two dependencies to your `pom.xml`. No tiko-side code needed.

## What it does

`Main` boots tiko, forces instantiation of a `FailingComponent` whose `@PreDestroy`
throws, then closes the container. The thrown exception is routed through
`DefaultErrorHandler`, which logs a WARNING via `java.lang.System.Logger`. Because
`slf4j-jdk-platform-logging` is on the classpath, that log call flows through slf4j
into logback, which formats and prints it using the pattern in `logback.xml`.

## The recipe

Add two deps to your `pom.xml`:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-jdk-platform-logging</artifactId>
</dependency>
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
</dependency>
```

Add a `logback.xml` on your classpath (this example's is in `src/main/resources/`).

That's it. Every framework log goes through slf4j → logback.

## Expected output when you run `Main`

```
WARN  [io.tiko.events] @PreDestroy on io.tiko.examples.logger.FailingComponent threw: java.lang.IllegalStateException: simulated teardown failure
[main] container closed cleanly
```

The `WARN [io.tiko.events]` prefix matches logback's configured pattern — JUL's
default format is recognizably different (`Aug 17, 2026 ... WARNING:`), so seeing this
shape proves the routing works.

## Other backends

Same pattern works for **log4j2** (`log4j-jpl` + `log4j-core`) and any other
`System.LoggerFinder` provider. See the project's main README under "Logging" for the
brief recipe per backend.
