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

## How to run

```
mvn -pl tiko-examples/11_custom_logger exec:exec
```

This uses `exec:exec` (forks a JVM) rather than `exec:java` (in-process). The reason matters
for this example: `java.lang.System.LoggerFinder` is resolved once per JVM via
`ServiceLoader.load(System.LoggerFinder.class, getSystemClassLoader())`. Under `mvn exec:java`,
the example's deps live on the exec plugin's child classloader, not the system classloader,
so `slf4j-jdk-platform-logging`'s SPI is invisible and the JDK binds to its default JUL
finder — every `System.Logger` call then formats with JUL's `WARNING:` prefix instead of
logback's pattern. `exec:exec` forks a real JVM whose system classpath includes the SPI
provider, so the bridge takes effect normally. The same caveat applies to any embedded host
that pre-resolves `System.LoggerFinder` before user code loads.

## Expected output when you run `Main`

```
WARN  [io.tiko.events] @PreDestroy threw on FailingComponent
java.lang.IllegalStateException: simulated teardown failure
    at io.tiko.examples.logger.FailingComponent.cleanup(FailingComponent.java:17)
    ... (stacktrace)
WARN  [io.tiko.events] @PreDestroy on io.tiko.examples.logger.FailingComponent threw: java.lang.IllegalStateException: simulated teardown failure
    at io.tiko.examples.logger.FailingComponent.cleanup(FailingComponent.java:17)
    ... (stacktrace)
[main] container closed cleanly
```

The `WARN [io.tiko.events]` prefix matches logback's configured pattern — JUL's
default format is recognizably different (`Aug 17, 2026 ... WARNING:`), so seeing this
shape proves the routing works.

Two WARN lines appear per `@PreDestroy` failure: the framework currently logs once at
the generated catch site and again via `DefaultErrorHandler`. This is pre-existing
duplicate-log behavior unrelated to the slf4j routing demo — both lines flow through
the same logback pipeline, which is what this recipe is showing.

## Other backends

Same pattern works for **log4j2** (`log4j-jpl` + `log4j-core`) and any other
`System.LoggerFinder` provider. See the project's main README under "Logging" for the
brief recipe per backend.
